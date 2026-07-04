package com.costlink.budget.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("budget_change_log")
public class BudgetChangeLog {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long budgetLineId;
    private String changeType;
    private BigDecimal changeAmount;
    private BigDecimal beforeAmount;
    private BigDecimal afterAmount;
    private Long sourceId;
    private String sourceType;
    private String remark;
    private LocalDateTime createTime;
}
