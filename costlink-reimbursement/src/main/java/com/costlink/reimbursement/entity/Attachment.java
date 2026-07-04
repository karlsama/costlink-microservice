package com.costlink.reimbursement.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("reimbursement_attachment")
public class Attachment {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long reimbursementId;
    private String fileName;
    private String fileUrl;
    private Long fileSize;
    private String fileHash;
    private BigDecimal ocrAmount;
    private String ocrStatus;
    private String ocrResult;
    @TableLogic
    private Integer deleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
