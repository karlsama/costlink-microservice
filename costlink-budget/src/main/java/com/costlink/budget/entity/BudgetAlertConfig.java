package com.costlink.budget.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("budget_alert_config")
public class BudgetAlertConfig {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long departmentId;
    private java.math.BigDecimal warningThreshold;
    private java.math.BigDecimal criticalThreshold;
    private String notifyRoles;
    private Integer enabled;
    @TableLogic
    private Integer deleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
