package com.costlink.approval.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("approval_record")
public class ApprovalRecord {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long instanceId;
    private Long nodeId;
    private Long operatorId;
    private String operatorName;
    private String action;
    private String comment;
    private String beforeStatus;
    private String afterStatus;
    private LocalDateTime createTime;
}
