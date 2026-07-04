package com.costlink.budget.service;

import com.costlink.budget.entity.Budget;
import com.costlink.common.dto.Result;
import com.costlink.common.feign.BudgetClient;

import java.util.List;

public interface BudgetService {
    // CRUD
    Result<Budget> create(Budget budget);
    Result<Budget> update(Long id, Budget budget);
    Result<Budget> getById(Long id);
    Result<?> page(int page, int size);
    Result<Void> delete(Long id);

    // 查询
    Result<?> getAvailable(Long deptId, String category);
    Result<?> getExecuteReport();

    // 内部 Feign 接口
    Result<BudgetClient.FreezeResponse> freeze(BudgetClient.FreezeRequest request);
    Result<Void> consume(BudgetClient.ConsumeRequest request);
    Result<Void> unfreeze(BudgetClient.UnfreezeRequest request);
}
