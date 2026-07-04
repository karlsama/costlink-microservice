package com.costlink.notification.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("message_template")
public class MessageTemplate {
    @TableId
    private Long id;
    private String templateCode;
    private String name;
    private String titleTemplate;
    private String contentTemplate;
    private Integer enabled;
    @TableLogic
    private Integer deleted;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
