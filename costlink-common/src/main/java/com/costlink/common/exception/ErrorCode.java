package com.costlink.common.exception;

import lombok.Getter;

/**
 * 统一错误码枚举 — 各组开发时从这里取错误码，禁止硬编码数字
 */
@Getter
public enum ErrorCode {

    // ========== 通用 10000-10099 ==========
    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证或Token已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    DUPLICATE_SUBMIT(10001, "请勿重复提交"),

    // ========== 报销模块 10100-10199 ==========
    REIMBURSEMENT_NOT_FOUND(10101, "报销单不存在"),
    REIMBURSEMENT_STATUS_ERROR(10102, "当前状态不允许此操作"),
    REIMBURSEMENT_AMOUNT_EXCEED(10103, "报销金额超过上限"),
    REIMBURSEMENT_WITHDRAW_TIMEOUT(10104, "已超过撤回时限"),
    REIMBURSEMENT_ITEMS_EMPTY(10105, "费用明细不能为空"),
    REIMBURSEMENT_NOT_OWNER(10106, "无权操作此报销单"),
    REIMBURSEMENT_INVALID_AMOUNT(10107, "金额不合法"),
    REIMBURSEMENT_BUDGET_FREEZE_FAILED(10108, "预算冻结失败"),
    REIMBURSEMENT_APPROVAL_START_FAILED(10109, "启动审批链失败"),

    // ========== 预算模块 10200-10299 ==========
    BUDGET_NOT_FOUND(10201, "预算不存在"),
    BUDGET_INSUFFICIENT(10202, "预算余额不足"),
    BUDGET_FREEZE_FAILED(10203, "预算冻结失败"),
    BUDGET_UNFREEZE_FAILED(10204, "预算解冻失败"),
    BUDGET_SERVICE_UNAVAILABLE(10205, "预算服务暂不可用"),

    // ========== 审批模块 10300-10399 ==========
    APPROVAL_NOT_FOUND(10301, "审批实例不存在"),
    APPROVAL_ALREADY_PROCESSED(10302, "该节点已被处理"),
    APPROVAL_NOT_AUTHORIZED(10303, "您不是当前审批人"),
    APPROVAL_TEMPLATE_NOT_FOUND(10304, "审批模板不存在"),
    APPROVAL_CHAIN_ERROR(10305, "审批链生成失败"),

    // ========== OCR模块 10400-10499 ==========
    OCR_RECOGNIZE_FAILED(10401, "票据识别失败"),
    OCR_UNSUPPORTED_FORMAT(10402, "不支持的图片格式"),
    OCR_QUOTA_EXCEEDED(10403, "OCR调用额度已用完"),
    OCR_SERVICE_UNAVAILABLE(10404, "OCR服务暂不可用"),

    // ========== 认证模块 10500-10599 ==========
    AUTH_LOGIN_FAILED(10501, "用户名或密码错误"),
    AUTH_TOKEN_EXPIRED(10502, "Token已过期，请重新登录"),
    AUTH_ACCOUNT_DISABLED(10503, "账号已被禁用"),
    AUTH_USER_NOT_FOUND(10504, "用户不存在"),

    // ========== 通知模块 10600-10699 ==========
    NOTIFICATION_SEND_FAILED(10601, "消息发送失败"),
    NOTIFICATION_TEMPLATE_NOT_FOUND(10602, "消息模板不存在");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
