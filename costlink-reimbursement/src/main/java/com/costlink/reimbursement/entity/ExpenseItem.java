package com.costlink.reimbursement.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("expense_item")
public class ExpenseItem {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long reimbursementId;
    private String category;
    private BigDecimal amount;
    private LocalDate receiptDate;
    private String remark;
    private Long attachmentId;
    @TableLogic
    private Integer deleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
