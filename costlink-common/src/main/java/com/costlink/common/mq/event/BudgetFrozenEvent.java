package com.costlink.common.mq.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 预算冻结事件 — 预算服务发布，通知服务消费（告知申请人预算已冻结）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BudgetFrozenEvent implements Serializable {

    /** 报销单ID */
    private Long reimbursementId;
    /** 申请人ID（收件人） */
    private Long applicantId;
    /** 报销事由 */
    private String title;
    /** 冻结金额 */
    private BigDecimal frozenAmount;
    /** 冻结后可用余额 */
    private BigDecimal availableAmount;
}
