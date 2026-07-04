package com.costlink.report.mapper.reimbursement;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface ReimbursementStatMapper {

    /** 报销汇总 */
    @Select("SELECT COUNT(*) AS total_count, " +
            "COALESCE(SUM(total_amount), 0) AS total_amount, " +
            "COALESCE(SUM(CASE WHEN status='APPROVED' THEN total_amount ELSE 0 END), 0) AS approved_amount, " +
            "COALESCE(SUM(CASE WHEN status='PAID' THEN total_amount ELSE 0 END), 0) AS paid_amount " +
            "FROM costlink_reimbursement.reimbursement " +
            "WHERE department_id = #{deptId} AND YEAR(submit_time) = #{year} AND deleted = 0")
    Map<String, Object> reimbursementSummary(@Param("deptId") Long deptId, @Param("year") int year);

    /** 部门费用排行 */
    @Select("SELECT department_id, COUNT(*) AS count, COALESCE(SUM(total_amount), 0) AS total_amount " +
            "FROM costlink_reimbursement.reimbursement " +
            "WHERE YEAR(submit_time) = #{year} AND MONTH(submit_time) = #{month} " +
            "AND status IN ('APPROVED', 'PAID') AND deleted = 0 " +
            "GROUP BY department_id ORDER BY total_amount DESC")
    List<Map<String, Object>> departmentRanking(@Param("year") int year, @Param("month") int month);

    /** 月度趋势 */
    @Select("SELECT MONTH(submit_time) AS month, COUNT(*) AS count, COALESCE(SUM(total_amount), 0) AS amount " +
            "FROM costlink_reimbursement.reimbursement " +
            "WHERE YEAR(submit_time) = #{year} AND department_id = #{deptId} " +
            "AND status IN ('APPROVED', 'PAID') AND deleted = 0 " +
            "GROUP BY MONTH(submit_time) ORDER BY month")
    List<Map<String, Object>> monthlyTrend(@Param("year") int year, @Param("deptId") Long deptId);

    /** 个人汇总 */
    @Select("SELECT applicant_id, COUNT(*) AS total_count, COALESCE(SUM(total_amount), 0) AS total_amount, " +
            "COALESCE(SUM(CASE WHEN status='PAID' THEN total_amount ELSE 0 END), 0) AS paid_amount " +
            "FROM costlink_reimbursement.reimbursement " +
            "WHERE applicant_id = #{userId} AND YEAR(submit_time) = #{year} AND deleted = 0 " +
            "GROUP BY applicant_id")
    Map<String, Object> personalSummary(@Param("userId") Long userId, @Param("year") int year);
}
