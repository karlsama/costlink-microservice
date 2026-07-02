package com.costlink.common.feign;

import com.costlink.common.dto.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

// ===================================================================
// Feign 接口 — 预算服务（报销服务调用）
// @FeignClient name 与 Nacos 注册的服务名一致
// ===================================================================

@FeignClient(name = "costlink-budget", path = "/internal/budgets")
public interface BudgetClient {

    /** 冻结预算金额（报销提交时同步调用） */
    @PostMapping("/freeze")
    Result<FreezeResponse> freeze(@Valid @RequestBody FreezeRequest request);

    /** 消费冻结金额（审批通过时异步或同步调用） */
    @PostMapping("/consume")
    Result<Void> consume(@Valid @RequestBody ConsumeRequest request);

    /** 解冻金额（审批驳回/撤回时调用） */
    @PostMapping("/unfreeze")
    Result<Void> unfreeze(@Valid @RequestBody UnfreezeRequest request);

    /** 查询可用余额 */
    @GetMapping("/available")
    Result<AvailableResponse> getAvailable(
            @RequestParam("departmentId") Long departmentId,
            @RequestParam("category") String category);

    // ---------- 请求/响应 DTO ----------

    @Data
    class FreezeRequest {
        @NotNull private Long reimbursementId;
        @NotEmpty private List<FreezeItem> items;
    }

    @Data
    class FreezeItem {
        @NotBlank private String category;
        @NotNull private BigDecimal amount;
    }

    @Data
    class FreezeResponse {
        private Boolean success;
        private BigDecimal availableAfterFreeze;
        private String controlStrategy;
        private String message;
    }

    @Data
    class ConsumeRequest {
        @NotNull private Long reimbursementId;
        @NotEmpty private List<ConsumeItem> items;
    }

    @Data
    class ConsumeItem {
        @NotBlank private String category;
        @NotNull private BigDecimal amount;
    }

    @Data
    class UnfreezeRequest {
        @NotNull private Long reimbursementId;
    }

    @Data
    class AvailableResponse {
        private Long departmentId;
        private String category;
        private BigDecimal totalAmount;
        private BigDecimal usedAmount;
        private BigDecimal frozenAmount;
        private BigDecimal availableAmount;
        private String status;  // NORMAL / WARNING / EXCEEDED
    }
}
