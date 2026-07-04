package com.costlink.budget.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.costlink.budget.entity.Budget;
import com.costlink.budget.mapper.BudgetMapper;
import com.costlink.budget.service.BudgetService;
import com.costlink.common.dto.Result;
import com.costlink.common.exception.BusinessException;
import com.costlink.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BudgetServiceImpl implements BudgetService {

    private final BudgetMapper budgetMapper;
    private final BudgetFreezeServiceImpl budgetFreezeService;

    @Override
    @Transactional
    public Result<Budget> create(Budget budget) {
        budgetMapper.insert(budget);
        log.info("预算创建成功, budgetId={}, deptId={}", budget.getId(), budget.getDepartmentId());
        return Result.ok(budget);
    }

    @Override
    @Transactional
    public Result<Budget> update(Long id, Budget budget) {
        Budget existing = budgetMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ErrorCode.BUDGET_NOT_FOUND);
        }
        budget.setId(id);
        budgetMapper.updateById(budget);
        log.info("预算更新成功, budgetId={}", id);
        return Result.ok(budget);
    }

    @Override
    public Result<Budget> getById(Long id) {
        Budget budget = budgetMapper.selectById(id);
        if (budget == null) {
            throw new BusinessException(ErrorCode.BUDGET_NOT_FOUND);
        }
        return Result.ok(budget);
    }

    @Override
    public Result<?> page(int page, int size) {
        Page<Budget> p = budgetMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Budget>().orderByDesc(Budget::getCreateTime));
        return Result.ok(p);
    }

    @Override
    @Transactional
    public Result<Void> delete(Long id) {
        Budget budget = budgetMapper.selectById(id);
        if (budget == null) {
            throw new BusinessException(ErrorCode.BUDGET_NOT_FOUND);
        }
        budgetMapper.deleteById(id);
        log.info("预算删除成功, budgetId={}", id);
        return Result.ok();
    }

    @Override
    public Result<?> getAvailable(Long deptId, String category) {
        return budgetFreezeService.getAvailable(deptId, category);
    }

    @Override
    public Result<?> getExecuteReport() {
        return Result.ok(budgetMapper.selectList(new LambdaQueryWrapper<>()));
    }

    @Override
    public Result<com.costlink.common.feign.BudgetClient.FreezeResponse> freeze(
            com.costlink.common.feign.BudgetClient.FreezeRequest request) {
        return budgetFreezeService.freeze(request);
    }

    @Override
    public Result<Void> consume(com.costlink.common.feign.BudgetClient.ConsumeRequest request) {
        budgetFreezeService.consume(request);
        return Result.ok();
    }

    @Override
    public Result<Void> unfreeze(com.costlink.common.feign.BudgetClient.UnfreezeRequest request) {
        budgetFreezeService.unfreeze(request);
        return Result.ok();
    }
}
