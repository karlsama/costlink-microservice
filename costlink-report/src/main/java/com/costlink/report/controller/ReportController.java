package com.costlink.report.controller;

import com.costlink.common.dto.Result;
import com.costlink.report.dto.*;
import com.costlink.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/reimbursement-summary")
    public Result<ReimbursementSummaryVO> reimbursementSummary(
            @RequestParam Long deptId, @RequestParam int year) {
        return Result.ok(reportService.reimbursementSummary(deptId, year));
    }

    @GetMapping("/budget-execution")
    public Result<List<BudgetExecutionVO>> budgetExecution(
            @RequestParam Long deptId, @RequestParam int year) {
        return Result.ok(reportService.budgetExecution(deptId, year));
    }

    @GetMapping("/department-ranking")
    public Result<List<DepartmentRankingVO>> departmentRanking(
            @RequestParam int year, @RequestParam int month) {
        return Result.ok(reportService.departmentRanking(year, month));
    }

    @GetMapping("/monthly-trend")
    public Result<List<MonthlyTrendVO>> monthlyTrend(
            @RequestParam int year, @RequestParam Long deptId) {
        return Result.ok(reportService.monthlyTrend(year, deptId));
    }

    @GetMapping("/personal-summary")
    public Result<PersonalSummaryVO> personalSummary(
            @RequestParam Long userId, @RequestParam int year) {
        return Result.ok(reportService.personalSummary(userId, year));
    }
}
