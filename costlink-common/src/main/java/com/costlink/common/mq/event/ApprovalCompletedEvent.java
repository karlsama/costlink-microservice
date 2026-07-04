package com.costlink.common.mq.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 审批完成事件 — 审批服务发布，报销服务 + 通知服务消费
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

    /** 报销事由（通知服务渲染模板用） */
    private String title;

    /** 报销金额（通知服务渲染模板用） */
    private BigDecimal amount;

    /** 申请人ID（通知服务发送消息的收件人） */
    private Long applicantId;
}
