package com.costlink.budget.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("budget_line")
public class BudgetLine {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long budgetId;
    private String category;
    private BigDecimal totalAmount;
    private BigDecimal usedAmount;
    private BigDecimal frozenAmount;
    private BigDecimal warningThreshold;
    private String controlStrategy;
    private Integer version;
    @TableLogic
    private Integer deleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
