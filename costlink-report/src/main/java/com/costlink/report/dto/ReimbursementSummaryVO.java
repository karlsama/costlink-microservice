package com.costlink.report.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class ReimbursementSummaryVO {
    private Long totalCount;
    private BigDecimal totalAmount;
    private BigDecimal approvedAmount;
    private BigDecimal paidAmount;
}
