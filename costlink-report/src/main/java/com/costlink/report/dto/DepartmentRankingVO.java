package com.costlink.report.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class DepartmentRankingVO {
    private Long departmentId;
    private Long count;
    private BigDecimal totalAmount;
}
