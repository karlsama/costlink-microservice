package com.costlink.common.mq.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 审批完成事件 — 审批服务发布，报销服务消费
 * 放在 common 中保证发布方和消费方使用同一个类定义
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalCompletedEvent implements Serializable {

    /** 报销单ID */
    private Long reimbursementId;

    /** 审批实例ID */
    private Long instanceId;

    /** 审批结果: APPROVED / REJECTED */
    private String action;
}
