package com.costlink.report.mapper.budget;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface BudgetStatMapper {

    /** 预算执行率 */
    @Select("SELECT bl.category, bl.total_amount, bl.used_amount, bl.frozen_amount, " +
            "ROUND(bl.used_amount / NULLIF(bl.total_amount, 0) * 100, 1) AS execute_rate " +
            "FROM costlink_budget.budget_line bl " +
            "JOIN costlink_budget.budget b ON bl.budget_id = b.id " +
            "WHERE b.department_id = #{deptId} AND b.fiscal_year = #{year} " +
            "AND b.deleted = 0 AND bl.deleted = 0")
    List<Map<String, Object>> budgetExecution(@Param("deptId") Long deptId, @Param("year") int year);
}
