package com.costlink.budget.controller;

import com.costlink.budget.entity.Budget;
import com.costlink.budget.service.BudgetService;
import com.costlink.common.dto.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/budgets")
@RequiredArgsConstructor
public class BudgetController {

    private final BudgetService budgetService;

    @PostMapping
    public Result<Budget> create(@Valid @RequestBody Budget budget) {
        return budgetService.create(budget);
    }

    @PutMapping("/{id}")
    public Result<Budget> update(@PathVariable Long id, @Valid @RequestBody Budget budget) {
        return budgetService.update(id, budget);
    }

    @GetMapping("/{id}")
    public Result<Budget> getById(@PathVariable Long id) {
        return budgetService.getById(id);
    }

    @GetMapping
    public Result<?> page(@RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "10") int size) {
        return budgetService.page(page, size);
    }

    @GetMapping("/available")
    public Result<?> getAvailable(@RequestParam Long deptId,
                                   @RequestParam(required = false) String category) {
        return budgetService.getAvailable(deptId, category);
    }

    @GetMapping("/execute-report")
    public Result<?> getExecuteReport() {
        return budgetService.getExecuteReport();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        return budgetService.delete(id);
    }
}
