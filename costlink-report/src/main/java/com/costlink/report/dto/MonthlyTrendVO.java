package com.costlink.report.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class MonthlyTrendVO {
    private Integer month;
    private Long count;
    private BigDecimal amount;
}
