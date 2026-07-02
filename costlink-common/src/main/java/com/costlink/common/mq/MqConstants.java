package com.costlink.common.mq;

/**
 * RabbitMQ 交换机与路由键常量 — 所有服务引用此常量，禁止硬编码字符串
 */
public final class MqConstants {

    private MqConstants() {}

    // ========== 交换机 ==========
    public static final String EXCHANGE_REIMBURSEMENT = "costlink.reimbursement";
    public static final String EXCHANGE_APPROVAL      = "costlink.approval";
    public static final String EXCHANGE_BUDGET        = "costlink.budget";
    public static final String EXCHANGE_OCR           = "costlink.ocr";

    // ========== 报销事件路由键 ==========
    /** 报销单已提交 → 预算服务(冻结) + OCR服务(识别) */
    public static final String RK_REIMBURSEMENT_SUBMITTED = "reimbursement.submitted";
    /** 报销单已通过 → 预算服务(消费冻结金额) */
    public static final String RK_REIMBURSEMENT_APPROVED  = "reimbursement.approved";
    /** 报销单已驳回 → 预算服务(解冻) */
    public static final String RK_REIMBURSEMENT_REJECTED  = "reimbursement.rejected";
    /** 报销单已付款 */
    public static final String RK_REIMBURSEMENT_PAID      = "reimbursement.paid";

    // ========== 审批事件路由键 ==========
    /** 审批流程完成(最终节点通过) → 报销服务 + 预算服务 */
    public static final String RK_APPROVAL_COMPLETED      = "approval.completed";
    /** 单个节点完成 → 通知服务(通知下一审批人) */
    public static final String RK_APPROVAL_NODE_COMPLETED = "approval.node.completed";

    // ========== 预算事件路由键 ==========
    /** 预算超支 → 通知服务 */
    public static final String RK_BUDGET_EXCEEDED         = "budget.exceeded";
    /** 预算冻结 → 通知服务 */
    public static final String RK_BUDGET_FROZEN           = "budget.frozen";

    // ========== OCR 事件路由键 ==========
    /** OCR 识别完成 → 报销服务(回写识别结果到 attachment 表) */
    public static final String RK_OCR_COMPLETED           = "ocr.completed";
    /** OCR 识别失败 → 报销服务(标记 attachment 为失败) */
    public static final String RK_OCR_FAILED              = "ocr.failed";

    // ========== 队列名 ==========
    public static final String QUEUE_REIMBURSEMENT_SUBMITTED = "q.reimbursement.submitted";
    public static final String QUEUE_REIMBURSEMENT_APPROVED  = "q.reimbursement.approved";
    public static final String QUEUE_REIMBURSEMENT_REJECTED  = "q.reimbursement.rejected";
    public static final String QUEUE_REIMBURSEMENT_PAID      = "q.reimbursement.paid";
    public static final String QUEUE_APPROVAL_COMPLETED      = "q.approval.completed";
    public static final String QUEUE_APPROVAL_NODE_COMPLETED = "q.approval.node.completed";
    public static final String QUEUE_BUDGET_EXCEEDED         = "q.budget.exceeded";
    public static final String QUEUE_BUDGET_FROZEN           = "q.budget.frozen";
    public static final String QUEUE_OCR_COMPLETED           = "q.ocr.completed";
    public static final String QUEUE_OCR_FAILED              = "q.ocr.failed";
}
