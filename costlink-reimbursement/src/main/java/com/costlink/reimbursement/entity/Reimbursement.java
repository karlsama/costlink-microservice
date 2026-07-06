package com.costlink.reimbursement.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("reimbursement")
public class Reimbursement implements Serializable {
    private static final long serialVersionUID = 1L;
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long applicantId;
    private Long departmentId;
    private String title;
    private BigDecimal totalAmount;
    private String expenseType;
    private String status;
    private Long approvalInstanceId;
    private String amountHash;
    private LocalDateTime submitTime;
    private LocalDateTime approveTime;
    private LocalDateTime paidTime;
    private String remark;
    @TableLogic
    private Integer deleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private List<ExpenseItem> expenseItems;
}
