package com.costlink.approval.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.costlink.approval.entity.ApprovalNode;
import com.costlink.approval.entity.ApprovalTemplate;
import com.costlink.approval.mapper.ApprovalNodeMapper;
import com.costlink.approval.mapper.ApprovalTemplateMapper;
import com.costlink.common.dto.Result;
import com.costlink.common.exception.BusinessException;
import com.costlink.common.exception.ErrorCode;
import com.costlink.common.feign.ApprovalClient;
import com.costlink.common.feign.AuthClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApprovalChainEngine {

    private final ApprovalTemplateMapper templateMapper;
    private final ApprovalNodeMapper nodeMapper;
    private final AuthClient authClient;
    private final ObjectMapper objectMapper;

    /**
     * 评估模板并生成审批节点列表
     * @return 生成的节点列表（尚未持久化到 DB）
     */
    public List<ApprovalNode> evaluateAndGenerateNodes(ApprovalClient.StartRequest req, Long instanceId) {
        // 1. 加载默认模板 (id=1)
        ApprovalTemplate template = templateMapper.selectById(1L);
        if (template == null) {
            throw new BusinessException(ErrorCode.APPROVAL_TEMPLATE_NOT_FOUND);
        }

        // 2. 解析 rules JSON
        List<RuleConfig> rules;
        try {
            rules = objectMapper.readValue(template.getRules(), new TypeReference<List<RuleConfig>>() {});
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.APPROVAL_CHAIN_ERROR);
        }

        // 3. 按 priority 排序，找到匹配金额的第一个规则
        RuleConfig matchedRule = rules.stream()
                .filter(r -> {
                    BigDecimal min = r.getCondition().getAmountMin();
                    BigDecimal max = r.getCondition().getAmountMax();
                    boolean geMin = (min == null || req.getTotalAmount().compareTo(min) >= 0);
                    boolean leMax = (max == null || req.getTotalAmount().compareTo(max) <= 0);
                    return geMin && leMax;
                })
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVAL_CHAIN_ERROR));

        log.info("匹配到审批规则 priority={}, 金额={}", matchedRule.getPriority(), req.getTotalAmount());

        // 4. 为每个 approver 生成节点
        List<ApprovalNode> nodes = new ArrayList<>();
        int order = 1;

        for (ApproverConfig approver : matchedRule.getApprovers()) {
            // 查认证服务：角色 → 具体人员
            List<AuthClient.UserInfoDTO> users;
            try {
                Result<List<AuthClient.UserInfoDTO>> result =
                        authClient.getUsersByRole(approver.getValue(), req.getDepartmentId());
                if (result.isSuccess() && result.getData() != null) {
                    users = result.getData();
                } else {
                    users = List.of();
                }
            } catch (Exception e) {
                log.error("查询审批人失败, role={}, deptId={}", approver.getValue(), req.getDepartmentId(), e);
                users = List.of();
            }

            if (users.isEmpty()) {
                log.warn("角色 {} 在部门 {} 下无用户", approver.getValue(), req.getDepartmentId());
                throw new BusinessException(ErrorCode.APPROVAL_CHAIN_ERROR,
                        "角色 " + approver.getValue() + " 无可用审批人");
            }

            // 取第一个用户（SINGLE 模式）
            AuthClient.UserInfoDTO user = users.get(0);

            ApprovalNode node = new ApprovalNode();
            node.setInstanceId(instanceId);
            node.setNodeOrder(order);
            node.setApproverId(user.getId());
            node.setApproverName(user.getDisplayName());
            node.setApproverRole(approver.getValue());
            node.setApproveMode(approver.getMode());
            node.setStatus("PENDING");

            // 自动跳过申请人本人
            boolean autoSkip = true; // from config, hardcode true for now
            if (autoSkip && user.getId().equals(req.getApplicantId())) {
                node.setStatus("SKIPPED");
                log.info("跳过本人节点, userId={}, role={}", user.getId(), approver.getValue());
            }

            nodes.add(node);
            order++;
        }

        return nodes;
    }

    /**
     * 保存节点到 DB，返回实际可用的第一个 PENDING 节点（未被 SKIPPED 的）
     */
    public ApprovalNode saveNodesAndGetFirstPending(List<ApprovalNode> nodes) {
        ApprovalNode firstPending = null;
        for (ApprovalNode node : nodes) {
            nodeMapper.insert(node);
            if (node.getStatus().equals("PENDING") && firstPending == null) {
                firstPending = node;
            }
        }
        return firstPending;
    }

    // ========== 规则 JSON 映射类 ==========

    public static class RuleConfig {
        private int priority;
        private ConditionConfig condition;
        private List<ApproverConfig> approvers;

        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
        public ConditionConfig getCondition() { return condition; }
        public void setCondition(ConditionConfig condition) { this.condition = condition; }
        public List<ApproverConfig> getApprovers() { return approvers; }
        public void setApprovers(List<ApproverConfig> approvers) { this.approvers = approvers; }
    }

    public static class ConditionConfig {
        private BigDecimal amountMin;
        private BigDecimal amountMax;

        public BigDecimal getAmountMin() { return amountMin; }
        public void setAmountMin(BigDecimal amountMin) { this.amountMin = amountMin; }
        public BigDecimal getAmountMax() { return amountMax; }
        public void setAmountMax(BigDecimal amountMax) { this.amountMax = amountMax; }
    }

    public static class ApproverConfig {
        private String type;
        private String value;
        private String mode;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
    }
}
