package com.costlink.budget.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.costlink.budget.entity.Budget;
import com.costlink.budget.entity.BudgetChangeLog;
import com.costlink.budget.entity.BudgetLine;
import com.costlink.budget.mapper.BudgetChangeLogMapper;
import com.costlink.budget.mapper.BudgetLineMapper;
import com.costlink.budget.mapper.BudgetMapper;
import com.costlink.common.dto.Result;
import com.costlink.common.exception.BusinessException;
import com.costlink.common.exception.ErrorCode;
import com.costlink.common.feign.BudgetClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetFreezeServiceImpl {

    private final BudgetMapper budgetMapper;
    private final BudgetLineMapper budgetLineMapper;
    private final BudgetChangeLogMapper changeLogMapper;
    private final RedissonClient redissonClient;

    /**
     * 冻结预算 — Redis 分布式锁 + MySQL 乐观锁双层防护
     */
    @Transactional
    public Result<BudgetClient.FreezeResponse> freeze(BudgetClient.FreezeRequest request) {
        // 1. 找到该部门当前财年的有效预算
        Budget budget = budgetMapper.selectOne(
                new LambdaQueryWrapper<Budget>()
                        .eq(Budget::getDepartmentId, request.getDepartmentId())
                        .eq(Budget::getFiscalYear, Year.now().getValue())
                        .eq(Budget::getStatus, "ACTIVE")
        );
        if (budget == null) {
            return Result.ok(newFreezeResponse(false, BigDecimal.ZERO, "STRICT",
                    "该部门无有效预算"));
        }

        // 2. 部门级 Redis 锁
        String lockKey = "budget:lock:" + request.getDepartmentId();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(3, 10, TimeUnit.SECONDS)) {
                return Result.ok(newFreezeResponse(false, BigDecimal.ZERO, "STRICT",
                        "系统繁忙，请稍后重试"));
            }

            for (BudgetClient.FreezeItem item : request.getItems()) {
                // 查预算明细（带乐观锁 version）
                BudgetLine line = budgetLineMapper.selectOne(
                        new LambdaQueryWrapper<BudgetLine>()
                                .eq(BudgetLine::getBudgetId, budget.getId())
                                .eq(BudgetLine::getCategory, item.getCategory())
                );
                if (line == null) {
                    lock.unlock();
                    return Result.ok(newFreezeResponse(false, BigDecimal.ZERO, "STRICT",
                            "科目 " + item.getCategory() + " 无预算"));
                }

                // 计算可用余额 = 总额 - 已用 - 已冻结
                BigDecimal available = line.getTotalAmount()
                        .subtract(line.getUsedAmount() != null ? line.getUsedAmount() : BigDecimal.ZERO)
                        .subtract(line.getFrozenAmount() != null ? line.getFrozenAmount() : BigDecimal.ZERO);

                // 硬控制：不足则拒绝
                if (available.compareTo(item.getAmount()) < 0) {
                    lock.unlock();
                    return Result.ok(newFreezeResponse(false, available, line.getControlStrategy(),
                            "预算不足，可用: " + available.toPlainString()));
                }

                // 更新冻结金额（乐观锁）
                int rows = budgetLineMapper.update(null,
                        new LambdaUpdateWrapper<BudgetLine>()
                                .eq(BudgetLine::getId, line.getId())
                                .eq(BudgetLine::getVersion, line.getVersion())
                                .setSql("frozen_amount = frozen_amount + " + item.getAmount())
                                .setSql("version = version + 1")
                );
                if (rows == 0) {
                    throw new BusinessException(ErrorCode.BUDGET_FREEZE_FAILED);
                }

                // 记录流水
                BigDecimal beforeFrozen = line.getFrozenAmount() != null ? line.getFrozenAmount() : BigDecimal.ZERO;
                saveChangeLog(line.getId(), "FREEZE", item.getAmount(),
                        beforeFrozen, beforeFrozen.add(item.getAmount()),
                        request.getReimbursementId(), "REIMBURSEMENT",
                        "报销单 #" + request.getReimbursementId() + " 冻结 " + item.getCategory());
            }

            log.info("预算冻结成功, reimbursementId={}, deptId={}",
                    request.getReimbursementId(), request.getDepartmentId());
            return Result.ok(newFreezeResponse(true, BigDecimal.ZERO, "STRICT", "预算冻结成功"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.ok(newFreezeResponse(false, BigDecimal.ZERO, "STRICT", "系统繁忙"));
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 消费冻结金额（审批通过后调用）
     */
    @Transactional
    public void consume(BudgetClient.ConsumeRequest request) {
        // 收集该报销单的所有冻结记录（含 budget_line_id）
        java.util.Map<String, BudgetChangeLog> freezeLogMap = changeLogMapper.selectList(
                new LambdaQueryWrapper<BudgetChangeLog>()
                        .eq(BudgetChangeLog::getSourceId, request.getReimbursementId())
                        .eq(BudgetChangeLog::getChangeType, "FREEZE")
        ).stream().collect(java.util.HashMap::new,
                (map, log) -> {
                    BudgetLine bl = budgetLineMapper.selectById(log.getBudgetLineId());
                    if (bl != null) map.put(bl.getCategory(), log);
                },
                java.util.Map::putAll);

        for (BudgetClient.ConsumeItem item : request.getItems()) {
            // 幂等检查
            Long count = changeLogMapper.selectCount(
                    new LambdaQueryWrapper<BudgetChangeLog>()
                            .eq(BudgetChangeLog::getSourceId, request.getReimbursementId())
                            .eq(BudgetChangeLog::getChangeType, "CONSUME")
                            .eq(BudgetChangeLog::getBudgetLineId,
                                    freezeLogMap.get(item.getCategory()) != null
                                            ? freezeLogMap.get(item.getCategory()).getBudgetLineId() : 0)
            );
            if (count > 0) {
                log.info("消费已处理（幂等跳过）, reimbursementId={}, category={}",
                        request.getReimbursementId(), item.getCategory());
                continue;
            }

            BudgetChangeLog freezeLog = freezeLogMap.get(item.getCategory());
            if (freezeLog == null) {
                log.warn("未找到对应冻结记录, reimbursementId={}, category={}",
                        request.getReimbursementId(), item.getCategory());
                continue;
            }

            BudgetLine line = budgetLineMapper.selectById(freezeLog.getBudgetLineId());
            if (line == null) continue;

            int rows = budgetLineMapper.update(null,
                    new LambdaUpdateWrapper<BudgetLine>()
                            .eq(BudgetLine::getId, line.getId())
                            .eq(BudgetLine::getVersion, line.getVersion())
                            .setSql("frozen_amount = frozen_amount - " + item.getAmount())
                            .setSql("used_amount = used_amount + " + item.getAmount())
                            .setSql("version = version + 1")
            );
            if (rows == 0) {
                throw new BusinessException(ErrorCode.BUDGET_FREEZE_FAILED);
            }

            BigDecimal beforeUsed = line.getUsedAmount() != null ? line.getUsedAmount() : BigDecimal.ZERO;
            saveChangeLog(line.getId(), "CONSUME", item.getAmount(),
                    beforeUsed, beforeUsed.add(item.getAmount()),
                    request.getReimbursementId(), "REIMBURSEMENT",
                    "报销单 #" + request.getReimbursementId() + " 消费 " + item.getCategory());
        }
        log.info("预算消费成功, reimbursementId={}", request.getReimbursementId());
    }

    /**
     * 解冻（驳回/撤回时调用）
     */
    @Transactional
    public void unfreeze(BudgetClient.UnfreezeRequest request) {
        java.util.List<BudgetChangeLog> freezeLogs = changeLogMapper.selectList(
                new LambdaQueryWrapper<BudgetChangeLog>()
                        .eq(BudgetChangeLog::getSourceId, request.getReimbursementId())
                        .eq(BudgetChangeLog::getChangeType, "FREEZE")
        );
        if (freezeLogs.isEmpty()) {
            log.info("无冻结记录可解冻（幂等跳过）, reimbursementId={}", request.getReimbursementId());
            return;
        }

        for (BudgetChangeLog log : freezeLogs) {
            BudgetLine line = budgetLineMapper.selectById(log.getBudgetLineId());
            if (line == null) continue;

            int rows = budgetLineMapper.update(null,
                    new LambdaUpdateWrapper<BudgetLine>()
                            .eq(BudgetLine::getId, line.getId())
                            .eq(BudgetLine::getVersion, line.getVersion())
                            .setSql("frozen_amount = frozen_amount - " + log.getChangeAmount())
                            .setSql("version = version + 1")
            );
            if (rows == 0) {
                throw new BusinessException(ErrorCode.BUDGET_UNFREEZE_FAILED);
            }

            BigDecimal beforeFrozen = line.getFrozenAmount() != null ? line.getFrozenAmount() : BigDecimal.ZERO;
            saveChangeLog(line.getId(), "UNFREEZE",
                    log.getChangeAmount().negate(),
                    beforeFrozen, beforeFrozen.subtract(log.getChangeAmount()),
                    request.getReimbursementId(), "REIMBURSEMENT",
                    "报销单 #" + request.getReimbursementId() + " 解冻");
        }
        log.info("预算解冻成功, reimbursementId={}", request.getReimbursementId());
    }

    /**
     * 查询可用余额
     */
    public Result<BudgetClient.AvailableResponse> getAvailable(Long deptId, String category) {
        Budget budget = budgetMapper.selectOne(
                new LambdaQueryWrapper<Budget>()
                        .eq(Budget::getDepartmentId, deptId)
                        .eq(Budget::getFiscalYear, Year.now().getValue())
                        .eq(Budget::getStatus, "ACTIVE")
        );
        if (budget == null) {
            BudgetClient.AvailableResponse resp = new BudgetClient.AvailableResponse();
            resp.setStatus("NO_BUDGET");
            return Result.ok(resp);
        }

        BudgetClient.AvailableResponse resp = new BudgetClient.AvailableResponse();
        resp.setDepartmentId(deptId);
        resp.setCategory(category);

        if (category != null) {
            BudgetLine line = budgetLineMapper.selectOne(
                    new LambdaQueryWrapper<BudgetLine>()
                            .eq(BudgetLine::getBudgetId, budget.getId())
                            .eq(BudgetLine::getCategory, category)
            );
            if (line != null) {
                resp.setTotalAmount(line.getTotalAmount());
                resp.setUsedAmount(line.getUsedAmount());
                resp.setFrozenAmount(line.getFrozenAmount());
                BigDecimal available = line.getTotalAmount()
                        .subtract(line.getUsedAmount() != null ? line.getUsedAmount() : BigDecimal.ZERO)
                        .subtract(line.getFrozenAmount() != null ? line.getFrozenAmount() : BigDecimal.ZERO);
                resp.setAvailableAmount(available);
                resp.setStatus(available.compareTo(BigDecimal.ZERO) > 0 ? "NORMAL" : "EXCEEDED");
            }
        } else {
            resp.setTotalAmount(budget.getTotalAmount());
            resp.setStatus("NORMAL");
        }
        return Result.ok(resp);
    }

    /**
     * 幂等检查 — 该报销单是否已处理过消费
     */
    public Long checkConsumeExists(Long reimbursementId) {
        return changeLogMapper.selectCount(
                new LambdaQueryWrapper<BudgetChangeLog>()
                        .eq(BudgetChangeLog::getSourceId, reimbursementId)
                        .eq(BudgetChangeLog::getChangeType, "CONSUME")
        );
    }

    private void saveChangeLog(Long budgetLineId, String changeType, BigDecimal changeAmount,
                                BigDecimal beforeAmount, BigDecimal afterAmount,
                                Long sourceId, String sourceType, String remark) {
        BudgetChangeLog log = new BudgetChangeLog();
        log.setBudgetLineId(budgetLineId);
        log.setChangeType(changeType);
        log.setChangeAmount(changeAmount);
        log.setBeforeAmount(beforeAmount);
        log.setAfterAmount(afterAmount);
        log.setSourceId(sourceId);
        log.setSourceType(sourceType);
        log.setRemark(remark);
        log.setCreateTime(LocalDateTime.now());
        changeLogMapper.insert(log);
    }

    private BudgetClient.FreezeResponse newFreezeResponse(boolean success, BigDecimal available,
                                                           String strategy, String message) {
        BudgetClient.FreezeResponse resp = new BudgetClient.FreezeResponse();
        resp.setSuccess(success);
        resp.setAvailableAfterFreeze(available);
        resp.setControlStrategy(strategy);
        resp.setMessage(message);
        return resp;
    }
}
