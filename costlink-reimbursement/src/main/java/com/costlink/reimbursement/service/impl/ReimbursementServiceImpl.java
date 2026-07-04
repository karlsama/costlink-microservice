package com.costlink.reimbursement.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.costlink.common.dto.Result;
import com.costlink.common.exception.BusinessException;
import com.costlink.common.exception.ErrorCode;
import com.costlink.common.feign.ApprovalClient;
import com.costlink.common.feign.BudgetClient;
import com.costlink.common.mq.MqConstants;
import com.costlink.reimbursement.dto.ReimbursementCreateRequest;
import com.costlink.reimbursement.dto.ReimbursementUpdateRequest;
import com.costlink.reimbursement.entity.ExpenseItem;
import com.costlink.reimbursement.entity.OutboxMessage;
import com.costlink.reimbursement.entity.Reimbursement;
import com.costlink.reimbursement.mapper.ExpenseItemMapper;
import com.costlink.reimbursement.mapper.OutboxMessageMapper;
import com.costlink.reimbursement.mapper.ReimbursementMapper;
import com.costlink.reimbursement.service.ReimbursementService;
import com.costlink.reimbursement.util.ReimbursementStatusMachine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReimbursementServiceImpl implements ReimbursementService {

    private final ReimbursementMapper reimbursementMapper;
    private final ExpenseItemMapper expenseItemMapper;
    private final OutboxMessageMapper outboxMessageMapper;
    private final BudgetClient budgetClient;
    private final ApprovalClient approvalClient;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public Result<Reimbursement> create(ReimbursementCreateRequest request, Long userId, Long departmentId) {
        Reimbursement r = new Reimbursement();
        r.setApplicantId(userId);
        r.setDepartmentId(departmentId);
        r.setTitle(request.getTitle());
        r.setExpenseType(request.getExpenseType());
        r.setRemark(request.getRemark());
        r.setStatus("DRAFT");

        BigDecimal total = request.getItems().stream()
                .map(ReimbursementCreateRequest.ItemDTO::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        r.setTotalAmount(total);

        reimbursementMapper.insert(r);

        for (ReimbursementCreateRequest.ItemDTO item : request.getItems()) {
            ExpenseItem ei = new ExpenseItem();
            ei.setReimbursementId(r.getId());
            ei.setCategory(item.getCategory());
            ei.setAmount(item.getAmount());
            ei.setReceiptDate(item.getReceiptDate());
            ei.setRemark(item.getRemark());
            ei.setAttachmentId(item.getAttachmentId());
            expenseItemMapper.insert(ei);
        }

        log.info("报销单创建成功, reimbursementId={}, userId={}", r.getId(), userId);
        return Result.ok(r);
    }

    @Override
    @Transactional
    public Result<Reimbursement> update(Long id, ReimbursementUpdateRequest request, Long userId) {
        Reimbursement r = reimbursementMapper.selectById(id);
        if (r == null) {
            throw new BusinessException(ErrorCode.REIMBURSEMENT_NOT_FOUND);
        }
        if (!"DRAFT".equals(r.getStatus())) {
            throw new BusinessException(ErrorCode.REIMBURSEMENT_STATUS_ERROR);
        }
        if (!r.getApplicantId().equals(userId)) {
            throw new BusinessException(ErrorCode.REIMBURSEMENT_NOT_OWNER);
        }

        if (request.getTitle() != null) r.setTitle(request.getTitle());
        if (request.getExpenseType() != null) r.setExpenseType(request.getExpenseType());
        if (request.getRemark() != null) r.setRemark(request.getRemark());

        if (request.getItems() != null && !request.getItems().isEmpty()) {
            expenseItemMapper.delete(new LambdaQueryWrapper<ExpenseItem>().eq(ExpenseItem::getReimbursementId, id));
            for (ReimbursementUpdateRequest.ItemDTO item : request.getItems()) {
                ExpenseItem ei = new ExpenseItem();
                ei.setReimbursementId(id);
                ei.setCategory(item.getCategory());
                ei.setAmount(item.getAmount());
                ei.setReceiptDate(item.getReceiptDate());
                ei.setRemark(item.getRemark());
                ei.setAttachmentId(item.getAttachmentId());
                expenseItemMapper.insert(ei);
            }
            BigDecimal total = request.getItems().stream()
                    .map(ReimbursementUpdateRequest.ItemDTO::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            r.setTotalAmount(total);
        }

        reimbursementMapper.updateById(r);
        log.info("报销单更新成功, reimbursementId={}, userId={}", id, userId);
        return Result.ok(r);
    }

    @Override
    public Result<Reimbursement> getById(Long id) {
        Reimbursement r = reimbursementMapper.selectById(id);
        if (r == null) {
            throw new BusinessException(ErrorCode.REIMBURSEMENT_NOT_FOUND);
        }
        List<ExpenseItem> items = expenseItemMapper.selectList(
                new LambdaQueryWrapper<ExpenseItem>().eq(ExpenseItem::getReimbursementId, id));
        r.setExpenseItems(items);  // Reimbursement.java needs this field
        return Result.ok(r);
    }

    @Override
    public Result<?> page(int page, int size, String status, Long userId) {
        LambdaQueryWrapper<Reimbursement> wrapper = new LambdaQueryWrapper<Reimbursement>()
                .eq(Reimbursement::getApplicantId, userId);
        if (StrUtil.isNotBlank(status)) {
            wrapper.eq(Reimbursement::getStatus, status);
        }
        wrapper.orderByDesc(Reimbursement::getCreateTime);

        Page<Reimbursement> p = reimbursementMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.ok(p);
    }

    @Override
    @Transactional
    public Result<Reimbursement> submit(Long id, Long userId) {
        Reimbursement r = reimbursementMapper.selectById(id);
        if (r == null) {
            throw new BusinessException(ErrorCode.REIMBURSEMENT_NOT_FOUND);
        }
        if (!"DRAFT".equals(r.getStatus())) {
            throw new BusinessException(ErrorCode.REIMBURSEMENT_STATUS_ERROR);
        }
        if (r.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.REIMBURSEMENT_INVALID_AMOUNT);
        }

        // Step 1: Create outbox message (PENDING)
        String messageId = UUID.randomUUID().toString();
        OutboxMessage outbox = new OutboxMessage();
        outbox.setMessageId(messageId);
        outbox.setAggregateId(id);
        outbox.setEventType(MqConstants.RK_REIMBURSEMENT_SUBMITTED);
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        try {
            outbox.setPayload(objectMapper.writeValueAsString(r));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化消息体失败", e);
        }
        outboxMessageMapper.insert(outbox);

        // Step 2: Freeze budget
        List<ExpenseItem> items = expenseItemMapper.selectList(
                new LambdaQueryWrapper<ExpenseItem>().eq(ExpenseItem::getReimbursementId, id));
        BudgetClient.FreezeRequest freezeReq = new BudgetClient.FreezeRequest();
        freezeReq.setReimbursementId(id);
        freezeReq.setDepartmentId(r.getDepartmentId());
        freezeReq.setItems(items.stream().map(item -> {
            BudgetClient.FreezeItem fi = new BudgetClient.FreezeItem();
            fi.setCategory(item.getCategory());
            fi.setAmount(item.getAmount());
            return fi;
        }).collect(Collectors.toList()));

        Result<BudgetClient.FreezeResponse> freezeResult;
        try {
            freezeResult = budgetClient.freeze(freezeReq);
        } catch (Exception e) {
            log.error("预算冻结失败, reimbursementId={}", id, e);
            throw new BusinessException(ErrorCode.REIMBURSEMENT_BUDGET_FREEZE_FAILED);
        }
        if (!freezeResult.isSuccess()) {
            throw new BusinessException(ErrorCode.REIMBURSEMENT_BUDGET_FREEZE_FAILED);
        }

        // Step 3: Start approval chain
        ApprovalClient.StartRequest startReq = new ApprovalClient.StartRequest();
        startReq.setReimbursementId(id);
        startReq.setApplicantId(userId);
        startReq.setDepartmentId(r.getDepartmentId());
        startReq.setTotalAmount(r.getTotalAmount());
        startReq.setExpenseType(r.getExpenseType());

        ApprovalClient.StartResponse startResp;
        try {
            Result<ApprovalClient.StartResponse> approvalResult = approvalClient.start(startReq);
            if (!approvalResult.isSuccess()) {
                throw new BusinessException(ErrorCode.REIMBURSEMENT_APPROVAL_START_FAILED);
            }
            startResp = approvalResult.getData();
        } catch (Exception e) {
            log.error("启动审批链失败, reimbursementId={}, 开始补偿解冻", id, e);
            compensateUnfreeze(id);
            throw new BusinessException(ErrorCode.REIMBURSEMENT_APPROVAL_START_FAILED);
        }

        // Step 4: Update reimbursement status
        r.setStatus("PENDING");
        r.setApprovalInstanceId(startResp.getInstanceId());
        r.setSubmitTime(LocalDateTime.now());
        r.setAmountHash(generateAmountHash(r));
        reimbursementMapper.updateById(r);

        // Step 5: Mark outbox as READY
        outbox.setStatus("READY");
        outboxMessageMapper.updateById(outbox);

        log.info("报销单提交成功, reimbursementId={}, userId={}, instanceId={}",
                id, userId, startResp.getInstanceId());
        return Result.ok(r);
    }

    @Override
    @Transactional
    public Result<Reimbursement> withdraw(Long id, Long userId) {
        Reimbursement r = reimbursementMapper.selectById(id);
        if (r == null) {
            throw new BusinessException(ErrorCode.REIMBURSEMENT_NOT_FOUND);
        }
        if (!ReimbursementStatusMachine.canTransition(r.getStatus(), "DRAFT")) {
            throw new BusinessException(ErrorCode.REIMBURSEMENT_STATUS_ERROR);
        }
        if (!r.getApplicantId().equals(userId)) {
            throw new BusinessException(ErrorCode.REIMBURSEMENT_NOT_OWNER);
        }

        r.setStatus("DRAFT");
        r.setApprovalInstanceId(null);
        r.setSubmitTime(null);
        reimbursementMapper.updateById(r);

        compensateUnfreeze(id);
        log.info("报销单撤回成功, reimbursementId={}, userId={}", id, userId);
        return Result.ok(r);
    }

    @Override
    @Transactional
    public Result<Reimbursement> markPaid(Long id, Long userId, Long operatorId) {
        Reimbursement r = reimbursementMapper.selectById(id);
        if (r == null) {
            throw new BusinessException(ErrorCode.REIMBURSEMENT_NOT_FOUND);
        }
        if (!ReimbursementStatusMachine.canTransition(r.getStatus(), "PAID")) {
            throw new BusinessException(ErrorCode.REIMBURSEMENT_STATUS_ERROR);
        }

        r.setStatus("PAID");
        r.setPaidTime(LocalDateTime.now());
        reimbursementMapper.updateById(r);
        log.info("报销单标记已付款, reimbursementId={}, operatorId={}", id, operatorId);
        return Result.ok(r);
    }

    @Override
    @Transactional
    public Result<Void> delete(Long id, Long userId) {
        Reimbursement r = reimbursementMapper.selectById(id);
        if (r == null) {
            throw new BusinessException(ErrorCode.REIMBURSEMENT_NOT_FOUND);
        }
        if (!r.getApplicantId().equals(userId)) {
            throw new BusinessException(ErrorCode.REIMBURSEMENT_NOT_OWNER);
        }
        reimbursementMapper.deleteById(id);
        log.info("报销单删除成功, reimbursementId={}, userId={}", id, userId);
        return Result.ok();
    }

    private void compensateUnfreeze(Long reimbursementId) {
        try {
            BudgetClient.UnfreezeRequest req = new BudgetClient.UnfreezeRequest();
            req.setReimbursementId(reimbursementId);
            budgetClient.unfreeze(req);
            log.info("预算解冻补偿完成, reimbursementId={}", reimbursementId);
        } catch (Exception e) {
            log.error("预算解冻补偿失败, reimbursementId={}", reimbursementId, e);
        }
    }

    private String generateAmountHash(Reimbursement r) {
        try {
            String data = r.getId() + "|" + r.getTotalAmount().toPlainString()
                    + "|" + r.getApplicantId() + "|" + LocalDateTime.now();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("CostLink-HMAC-Key-2026".getBytes(), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes()));
        } catch (Exception e) {
            log.warn("生成金额哈希失败", e);
            return null;
        }
    }
}
