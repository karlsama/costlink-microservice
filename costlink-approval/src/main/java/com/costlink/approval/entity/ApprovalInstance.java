package com.costlink.approval.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("approval_instance")
public class ApprovalInstance {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long templateId;
    private Long reimbursementId;
    private Long applicantId;
    private Long departmentId;
    private BigDecimal totalAmount;
    private String expenseType;
    private String status;
    private Integer currentNodeOrder;
    private Integer totalNodes;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    @TableLogic
    private Integer deleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
