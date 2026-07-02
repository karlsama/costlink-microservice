package com.costlink.common.feign;

import com.costlink.common.dto.Result;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Feign 接口 — 审批服务（报销服务调用）
 */
@FeignClient(name = "costlink-approval", path = "/internal/approvals")
public interface ApprovalClient {

    /** 启动审批链（报销提交时同步调用） */
    @PostMapping("/start")
    Result<StartResponse> start(@RequestBody StartRequest request);

    /** 查询审批进度 */
    @GetMapping("/instance/{instanceId}")
    Result<InstanceResponse> getInstance(@PathVariable Long instanceId);

    /** 查询我的待办 */
    @GetMapping("/pending")
    Result<List<PendingItem>> getPending(@RequestParam Long approverId);

    // ---------- DTOs ----------

    @Data
    class StartRequest {
        @NotNull private Long reimbursementId;
        @NotNull private Long applicantId;
        @NotNull private Long departmentId;
        @NotNull private BigDecimal totalAmount;
        @NotBlank private String expenseType;
        private Long templateId;  // 可选，不传则用默认模板
    }

    @Data
    class StartResponse {
        private Long instanceId;
        private String currentApprover;
        private Long currentApproverId;
        private List<NodeInfo> nodeChain;
    }

    @Data
    class NodeInfo {
        private Integer nodeOrder;
        private Long approverId;
        private String approverName;
        private String approverRole;
        private String approveMode; // SINGLE / COUNTERSIGN / OR_SIGN
    }

    @Data
    class InstanceResponse {
        private Long instanceId;
        private Long reimbursementId;
        private String status;       // IN_PROGRESS / APPROVED / REJECTED
        private Integer currentNodeOrder;
        private Integer totalNodes;
        private List<NodeDetail> nodes;
    }

    @Data
    class NodeDetail {
        private Long nodeId;
        private Integer nodeOrder;
        private String approverName;
        private String status;       // PENDING / APPROVED / REJECTED
        private String action;       // APPROVE / REJECT / TRANSFER
        private String comment;
        private String actionTime;
    }

    @Data
    class PendingItem {
        private Long instanceId;
        private Long reimbursementId;
        private String title;
        private BigDecimal amount;
        private String applicantName;
        private String submitTime;
    }
}
