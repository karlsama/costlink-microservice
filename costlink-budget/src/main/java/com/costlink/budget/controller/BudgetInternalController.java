package com.costlink.budget.controller;

import com.costlink.common.dto.Result;
import com.costlink.common.feign.BudgetClient;
import com.costlink.budget.service.BudgetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 内部 Feign 接口实现 — 报销服务通过 BudgetClient 调用
 * 注意：不要 implements BudgetClient（Feign 接口不能被 Controller 实现）
 */
@RestController
@RequestMapping("/internal/budgets")
@RequiredArgsConstructor
public class BudgetInternalController {

    private final BudgetService budgetService;

    @PostMapping("/freeze")
    public Result<BudgetClient.FreezeResponse> freeze(@RequestBody BudgetClient.FreezeRequest request) {
        return budgetService.freeze(request);
    }

    @PostMapping("/consume")
    public Result<Void> consume(@RequestBody BudgetClient.ConsumeRequest request) {
        return budgetService.consume(request);
    }

    @PostMapping("/unfreeze")
    public Result<Void> unfreeze(@RequestBody BudgetClient.UnfreezeRequest request) {
        return budgetService.unfreeze(request);
    }

    @GetMapping("/available")
    @SuppressWarnings("unchecked")
    public Result<BudgetClient.AvailableResponse> getAvailable(
            @RequestParam Long departmentId,
            @RequestParam String category) {
        return (Result<BudgetClient.AvailableResponse>) budgetService.getAvailable(departmentId, category);
    }
}
