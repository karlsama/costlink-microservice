package com.costlink.report.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class BudgetExecutionVO {
    private String category;
    private BigDecimal totalAmount;
    private BigDecimal usedAmount;
    private BigDecimal frozenAmount;
    private Double executeRate;
}
