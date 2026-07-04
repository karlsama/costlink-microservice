package com.costlink.report.service.impl;

import com.costlink.report.dto.*;
import com.costlink.report.mapper.budget.BudgetStatMapper;
import com.costlink.report.mapper.reimbursement.ReimbursementStatMapper;
import com.costlink.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final ReimbursementStatMapper reimbursementStatMapper;
    private final BudgetStatMapper budgetStatMapper;

    @Override
    public ReimbursementSummaryVO reimbursementSummary(Long deptId, int year) {
        Map<String, Object> row = reimbursementStatMapper.reimbursementSummary(deptId, year);
        return new ReimbursementSummaryVO(
                toLong(row.get("total_count")),
                toBigDecimal(row.get("total_amount")),
                toBigDecimal(row.get("approved_amount")),
                toBigDecimal(row.get("paid_amount"))
        );
    }

    @Override
    public List<BudgetExecutionVO> budgetExecution(Long deptId, int year) {
        List<Map<String, Object>> rows = budgetStatMapper.budgetExecution(deptId, year);
        return rows.stream().map(r -> new BudgetExecutionVO(
                str(r.get("category")),
                toBigDecimal(r.get("total_amount")),
                toBigDecimal(r.get("used_amount")),
                toBigDecimal(r.get("frozen_amount")),
                toDouble(r.get("execute_rate"))
        )).collect(Collectors.toList());
    }

    @Override
    public List<DepartmentRankingVO> departmentRanking(int year, int month) {
        List<Map<String, Object>> rows = reimbursementStatMapper.departmentRanking(year, month);
        return rows.stream().map(r -> new DepartmentRankingVO(
                toLong(r.get("department_id")),
                toLong(r.get("count")),
                toBigDecimal(r.get("total_amount"))
        )).collect(Collectors.toList());
    }

    @Override
    public List<MonthlyTrendVO> monthlyTrend(int year, Long deptId) {
        List<Map<String, Object>> rows = reimbursementStatMapper.monthlyTrend(year, deptId);
        return rows.stream().map(r -> new MonthlyTrendVO(
                toInt(r.get("month")),
                toLong(r.get("count")),
                toBigDecimal(r.get("amount"))
        )).collect(Collectors.toList());
    }

    @Override
    public PersonalSummaryVO personalSummary(Long userId, int year) {
        Map<String, Object> row = reimbursementStatMapper.personalSummary(userId, year);
        return new PersonalSummaryVO(
                toLong(row.get("applicant_id")),
                toLong(row.get("total_count")),
                toBigDecimal(row.get("total_amount")),
                toBigDecimal(row.get("paid_amount"))
        );
    }

    private Long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        return Long.valueOf(v.toString());
    }

    private Integer toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        return Integer.valueOf(v.toString());
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        return new BigDecimal(v.toString());
    }

    private Double toDouble(Object v) {
        if (v == null) return 0.0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        return Double.parseDouble(v.toString());
    }

    private String str(Object v) {
        return v != null ? v.toString() : "";
    }
}
