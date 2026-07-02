package com.costlink.common.dto;

import com.costlink.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应封装 — 所有 Controller 返回此类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    private int code;
    private String message;
    private T data;
    private long timestamp;

    // ========== 成功 ==========

    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "操作成功", data, System.currentTimeMillis());
    }

    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(200, message, data, System.currentTimeMillis());
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    // ========== 失败 ==========

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null, System.currentTimeMillis());
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getMessage());
    }

    // ========== 判断 ==========

    public boolean isSuccess() {
        return this.code == 200;
    }
}
