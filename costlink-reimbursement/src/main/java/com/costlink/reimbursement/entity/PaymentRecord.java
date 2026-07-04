package com.costlink.reimbursement.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("payment_record")
public class PaymentRecord {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long reimbursementId;
    private Long payeeId;
    private BigDecimal amount;
    private String payMethod;
    private String payAccount;
    private String payStatus;
    private LocalDateTime payTime;
    private String transactionId;
    private Long operatorId;
    private String remark;
    @TableLogic
    private Integer deleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
