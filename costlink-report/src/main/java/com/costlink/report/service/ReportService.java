package com.costlink.report.service;

import com.costlink.report.dto.*;

import java.util.List;

public interface ReportService {
    ReimbursementSummaryVO reimbursementSummary(Long deptId, int year);
    List<BudgetExecutionVO> budgetExecution(Long deptId, int year);
    List<DepartmentRankingVO> departmentRanking(int year, int month);
    List<MonthlyTrendVO> monthlyTrend(int year, Long deptId);
    PersonalSummaryVO personalSummary(Long userId, int year);
}
