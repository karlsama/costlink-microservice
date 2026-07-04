package com.costlink.approval.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("approval_node")
public class ApprovalNode {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long instanceId;
    private Integer nodeOrder;
    private Long approverId;
    private String approverName;
    private String approverRole;
    private String approveMode;
    private String status;
    private String action;
    private String comment;
    private LocalDateTime deadline;
    private LocalDateTime actionTime;
    @TableLogic
    private Integer deleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
