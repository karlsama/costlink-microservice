package com.costlink.approval.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.costlink.approval.entity.ApprovalInstance;
import com.costlink.approval.entity.ApprovalNode;
import com.costlink.approval.entity.ApprovalRecord;
import com.costlink.approval.mapper.ApprovalInstanceMapper;
import com.costlink.approval.mapper.ApprovalNodeMapper;
import com.costlink.approval.mapper.ApprovalRecordMapper;
import com.costlink.approval.mq.ApprovalEventPublisher;
import com.costlink.approval.service.ApprovalService;
import com.costlink.common.dto.Result;
import com.costlink.common.exception.BusinessException;
import com.costlink.common.exception.ErrorCode;
import com.costlink.common.feign.ApprovalClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApprovalServiceImpl implements ApprovalService {

    private final ApprovalInstanceMapper instanceMapper;
    private final ApprovalNodeMapper nodeMapper;
    private final ApprovalRecordMapper recordMapper;
    private final ApprovalChainEngine chainEngine;
    private final ApprovalEventPublisher eventPublisher;

    @Override
    @Transactional
    public Result<ApprovalClient.StartResponse> start(ApprovalClient.StartRequest request) {
        // 1. 创建实例（先获取 ID）
        ApprovalInstance inst = new ApprovalInstance();
        inst.setTemplateId(1L);
        inst.setReimbursementId(request.getReimbursementId());
        inst.setApplicantId(request.getApplicantId());
        inst.setDepartmentId(request.getDepartmentId());
        inst.setTotalAmount(request.getTotalAmount());
        inst.setExpenseType(request.getExpenseType());
        inst.setStatus("IN_PROGRESS");
        inst.setStartTime(LocalDateTime.now());
        instanceMapper.insert(inst);

        // 2. 评估模板生成节点
        List<ApprovalNode> nodes = chainEngine.evaluateAndGenerateNodes(request, inst.getId());

        // 3. 保存节点，找到第一个 PENDING
        ApprovalNode firstPending = chainEngine.saveNodesAndGetFirstPending(nodes);

        // 4. 更新实例
        inst.setTotalNodes(nodes.size());
        if (firstPending != null) {
            inst.setCurrentNodeOrder(firstPending.getNodeOrder());
        } else {
            // 全部跳过（申请人=唯一审批人）→ 直接 APPROVED
            inst.setStatus("APPROVED");
            inst.setCurrentNodeOrder(0);
            inst.setEndTime(LocalDateTime.now());
            eventPublisher.publishApprovalCompleted(inst, "APPROVED");
        }
        instanceMapper.updateById(inst);

        // 5. 通知第一个审批人
        if (firstPending != null) {
            eventPublisher.publishNodeCompleted(inst, firstPending);
        }

        log.info("审批链启动成功, instanceId={}, reimbursementId={}, nodes={}",
                inst.getId(), request.getReimbursementId(), nodes.size());

        // 6. 构建响应
        ApprovalClient.StartResponse resp = new ApprovalClient.StartResponse();
        resp.setInstanceId(inst.getId());
        if (firstPending != null) {
            resp.setCurrentApprover(firstPending.getApproverName());
            resp.setCurrentApproverId(firstPending.getApproverId());
        }
        resp.setNodeChain(nodes.stream().map(n -> {
            ApprovalClient.NodeInfo ni = new ApprovalClient.NodeInfo();
            ni.setNodeOrder(n.getNodeOrder());
            ni.setApproverId(n.getApproverId());
            ni.setApproverName(n.getApproverName());
            ni.setApproverRole(n.getApproverRole());
            ni.setApproveMode(n.getApproveMode());
            return ni;
        }).toList());

        return Result.ok(resp);
    }

    @Override
    @Transactional
    public Result<?> approve(Long instanceId, Long operatorId, String comment) {
        ApprovalInstance inst = instanceMapper.selectById(instanceId);
        if (inst == null) {
            throw new BusinessException(ErrorCode.APPROVAL_NOT_FOUND);
        }
        if (!"IN_PROGRESS".equals(inst.getStatus())) {
            throw new BusinessException(ErrorCode.APPROVAL_ALREADY_PROCESSED);
        }

        // 找到当前待审批节点
        ApprovalNode node = nodeMapper.selectOne(
                new LambdaQueryWrapper<ApprovalNode>()
                        .eq(ApprovalNode::getInstanceId, instanceId)
                        .eq(ApprovalNode::getNodeOrder, inst.getCurrentNodeOrder())
                        .eq(ApprovalNode::getStatus, "PENDING"));
        if (node == null) {
            throw new BusinessException(ErrorCode.APPROVAL_ALREADY_PROCESSED);
        }
        if (!node.getApproverId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.APPROVAL_NOT_AUTHORIZED);
        }

        // 更新节点
        String beforeStatus = node.getStatus();
        node.setStatus("APPROVED");
        node.setAction("APPROVE");
        node.setComment(comment);
        node.setActionTime(LocalDateTime.now());
        nodeMapper.updateById(node);

        // 写记录
        saveRecord(instanceId, node.getId(), operatorId, node.getApproverName(),
                "APPROVE", comment, beforeStatus, "APPROVED");

        // 判断是否最后一个节点
        boolean isLastNode = isLastNode(inst, node);
        if (isLastNode) {
            inst.setStatus("APPROVED");
            inst.setCurrentNodeOrder(inst.getCurrentNodeOrder() + 1);
            inst.setEndTime(LocalDateTime.now());
            instanceMapper.updateById(inst);
            eventPublisher.publishApprovalCompleted(inst, "APPROVED");
            log.info("审批通过（终局）, instanceId={}, reimbursementId={}", instanceId, inst.getReimbursementId());
        } else {
            inst.setCurrentNodeOrder(inst.getCurrentNodeOrder() + 1);
            instanceMapper.updateById(inst);
            // 通知下一节点
            ApprovalNode nextNode = nodeMapper.selectOne(
                    new LambdaQueryWrapper<ApprovalNode>()
                            .eq(ApprovalNode::getInstanceId, instanceId)
                            .eq(ApprovalNode::getNodeOrder, inst.getCurrentNodeOrder())
                            .eq(ApprovalNode::getStatus, "PENDING"));
            if (nextNode != null) {
                eventPublisher.publishNodeCompleted(inst, nextNode);
            }
            log.info("审批节点通过, instanceId={}, nodeOrder={}", instanceId, node.getNodeOrder());
        }

        return Result.ok();
    }

    @Override
    @Transactional
    public Result<?> reject(Long instanceId, Long operatorId, String comment) {
        ApprovalInstance inst = instanceMapper.selectById(instanceId);
        if (inst == null) {
            throw new BusinessException(ErrorCode.APPROVAL_NOT_FOUND);
        }
        if (!"IN_PROGRESS".equals(inst.getStatus())) {
            throw new BusinessException(ErrorCode.APPROVAL_ALREADY_PROCESSED);
        }

        ApprovalNode node = nodeMapper.selectOne(
                new LambdaQueryWrapper<ApprovalNode>()
                        .eq(ApprovalNode::getInstanceId, instanceId)
                        .eq(ApprovalNode::getNodeOrder, inst.getCurrentNodeOrder())
                        .eq(ApprovalNode::getStatus, "PENDING"));
        if (node == null) {
            throw new BusinessException(ErrorCode.APPROVAL_ALREADY_PROCESSED);
        }
        if (!node.getApproverId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.APPROVAL_NOT_AUTHORIZED);
        }

        String beforeStatus = node.getStatus();
        node.setStatus("REJECTED");
        node.setAction("REJECT");
        node.setComment(comment);
        node.setActionTime(LocalDateTime.now());
        nodeMapper.updateById(node);

        saveRecord(instanceId, node.getId(), operatorId, node.getApproverName(),
                "REJECT", comment, beforeStatus, "REJECTED");

        inst.setStatus("REJECTED");
        inst.setEndTime(LocalDateTime.now());
        instanceMapper.updateById(inst);

        eventPublisher.publishApprovalCompleted(inst, "REJECTED");
        log.info("审批驳回, instanceId={}, reimbursementId={}", instanceId, inst.getReimbursementId());
        return Result.ok();
    }

    @Override
    @Transactional
    public Result<?> transfer(Long instanceId, Long operatorId, Long newApproverId, String comment) {
        ApprovalInstance inst = instanceMapper.selectById(instanceId);
        if (inst == null) {
            throw new BusinessException(ErrorCode.APPROVAL_NOT_FOUND);
        }

        ApprovalNode node = nodeMapper.selectOne(
                new LambdaQueryWrapper<ApprovalNode>()
                        .eq(ApprovalNode::getInstanceId, instanceId)
                        .eq(ApprovalNode::getNodeOrder, inst.getCurrentNodeOrder())
                        .eq(ApprovalNode::getStatus, "PENDING"));
        if (node == null) {
            throw new BusinessException(ErrorCode.APPROVAL_ALREADY_PROCESSED);
        }
        if (!node.getApproverId().equals(operatorId)) {
            throw new BusinessException(ErrorCode.APPROVAL_NOT_AUTHORIZED);
        }

        // 当前节点标记 TRANSFERRED
        String beforeStatus = node.getStatus();
        node.setStatus("TRANSFERRED");
        node.setAction("TRANSFER");
        node.setComment(comment);
        node.setActionTime(LocalDateTime.now());
        nodeMapper.updateById(node);

        // 创建新节点
        ApprovalNode newNode = new ApprovalNode();
        newNode.setInstanceId(instanceId);
        newNode.setNodeOrder(node.getNodeOrder());
        newNode.setApproverId(newApproverId);
        newNode.setApproverName("审批人"); // caller should provide name
        newNode.setApproverRole(node.getApproverRole());
        newNode.setApproveMode(node.getApproveMode());
        newNode.setStatus("PENDING");
        nodeMapper.insert(newNode);

        saveRecord(instanceId, node.getId(), operatorId, node.getApproverName(),
                "TRANSFER", comment, beforeStatus, "TRANSFERRED");

        eventPublisher.publishNodeCompleted(inst, newNode);
        log.info("转审成功, instanceId={}, from={}, to={}", instanceId, operatorId, newApproverId);
        return Result.ok();
    }

    @Override
    public Result<?> getPending(Long approverId) {
        List<ApprovalNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<ApprovalNode>()
                        .eq(ApprovalNode::getApproverId, approverId)
                        .eq(ApprovalNode::getStatus, "PENDING"));

        List<Map<String, Object>> result = nodes.stream().map(n -> {
            ApprovalInstance inst = instanceMapper.selectById(n.getInstanceId());
            if (inst == null) return null;
            return Map.<String, Object>of(
                    "instanceId", n.getInstanceId(),
                    "nodeId", n.getId(),
                    "reimbursementId", inst.getReimbursementId(),
                    "title", "报销单 #" + inst.getReimbursementId(),
                    "amount", inst.getTotalAmount(),
                    "applicantId", inst.getApplicantId(),
                    "submitTime", inst.getStartTime()
            );
        }).filter(r -> r != null).collect(Collectors.toList());

        return Result.ok(result);
    }

    @Override
    public Result<ApprovalInstance> getInstanceDetail(Long instanceId) {
        ApprovalInstance inst = instanceMapper.selectById(instanceId);
        if (inst == null) {
            throw new BusinessException(ErrorCode.APPROVAL_NOT_FOUND);
        }
        List<ApprovalNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<ApprovalNode>().eq(ApprovalNode::getInstanceId, instanceId));
        List<ApprovalRecord> records = recordMapper.selectList(
                new LambdaQueryWrapper<ApprovalRecord>().eq(ApprovalRecord::getInstanceId, instanceId));

        inst.setExpenseType(nodes.toString()); // placeholder
        return Result.ok(inst);
    }

    @Override
    public Result<ApprovalClient.InstanceResponse> getInstance(Long instanceId) {
        ApprovalInstance inst = instanceMapper.selectById(instanceId);
        if (inst == null) {
            throw new BusinessException(ErrorCode.APPROVAL_NOT_FOUND);
        }
        return Result.ok(toInstanceResponse(inst));
    }

    @Override
    public Result<java.util.List<ApprovalClient.PendingItem>> getPendingList(Long approverId) {
        List<ApprovalNode> nodes = nodeMapper.selectList(
                new LambdaQueryWrapper<ApprovalNode>()
                        .eq(ApprovalNode::getApproverId, approverId)
                        .eq(ApprovalNode::getStatus, "PENDING"));
        List<ApprovalClient.PendingItem> items = nodes.stream().map(n -> {
            ApprovalInstance inst = instanceMapper.selectById(n.getInstanceId());
            if (inst == null) return null;
            ApprovalClient.PendingItem item = new ApprovalClient.PendingItem();
            item.setInstanceId(n.getInstanceId());
            item.setReimbursementId(inst.getReimbursementId());
            item.setAmount(inst.getTotalAmount());
            return item;
        }).filter(java.util.Objects::nonNull).toList();
        return Result.ok(items);
    }

    private ApprovalClient.InstanceResponse toInstanceResponse(ApprovalInstance inst) {
        ApprovalClient.InstanceResponse resp = new ApprovalClient.InstanceResponse();
        resp.setInstanceId(inst.getId());
        resp.setReimbursementId(inst.getReimbursementId());
        resp.setStatus(inst.getStatus());
        resp.setCurrentNodeOrder(inst.getCurrentNodeOrder());
        resp.setTotalNodes(inst.getTotalNodes());
        return resp;
    }

    private boolean isLastNode(ApprovalInstance inst, ApprovalNode currentNode) {
        List<ApprovalNode> allNodes = nodeMapper.selectList(
                new LambdaQueryWrapper<ApprovalNode>()
                        .eq(ApprovalNode::getInstanceId, inst.getId()));
        return allNodes.stream()
                .filter(n -> !"SKIPPED".equals(n.getStatus()))
                .allMatch(n -> n.getNodeOrder().equals(currentNode.getNodeOrder())
                        || "APPROVED".equals(n.getStatus()));
    }

    private void saveRecord(Long instanceId, Long nodeId, Long operatorId, String operatorName,
                             String action, String comment, String beforeStatus, String afterStatus) {
        ApprovalRecord record = new ApprovalRecord();
        record.setInstanceId(instanceId);
        record.setNodeId(nodeId);
        record.setOperatorId(operatorId);
        record.setOperatorName(operatorName);
        record.setAction(action);
        record.setComment(comment);
        record.setBeforeStatus(beforeStatus);
        record.setAfterStatus(afterStatus);
        record.setCreateTime(LocalDateTime.now());
        recordMapper.insert(record);
    }
}
