package com.costlink.common.mq.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 审批节点完成事件 — 审批服务发布，通知服务消费
 * 通知下一审批人"你有新的待审批任务"
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalNodeCompletedEvent implements Serializable {

    /** 报销单ID */
    private Long reimbursementId;
    /** 审批实例ID */
    private Long instanceId;
    /** 报销事由 */
    private String title;
    /** 报销金额 */
    private BigDecimal amount;
    /** 下一审批人ID（收件人） */
    private Long nextApproverId;
    /** 下一审批人姓名 */
    private String nextApproverName;
}
