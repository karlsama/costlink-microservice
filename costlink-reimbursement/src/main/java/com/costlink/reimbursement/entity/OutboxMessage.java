package com.costlink.reimbursement.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("outbox_message")
public class OutboxMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageId;
    private Long aggregateId;
    private String eventType;
    private String payload;
    private String status;
    private Integer retryCount;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime sentTime;
}
