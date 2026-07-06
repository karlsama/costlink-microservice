package com.costlink.approval.controller;

import com.costlink.approval.service.ApprovalService;
import com.costlink.common.dto.Result;
import com.costlink.common.feign.ApprovalClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/internal/approvals")
@RequiredArgsConstructor
public class ApprovalInternalController {

    private final ApprovalService approvalService;

    @PostMapping("/start")
    public Result<ApprovalClient.StartResponse> start(@RequestBody ApprovalClient.StartRequest request) {
        return approvalService.start(request);
    }

    @GetMapping("/instance/{instanceId}")
    public Result<ApprovalClient.InstanceResponse> getInstance(@PathVariable Long instanceId) {
        return approvalService.getInstance(instanceId);
    }

    @GetMapping("/pending")
    public Result<List<ApprovalClient.PendingItem>> getPending(@RequestParam Long approverId) {
        return approvalService.getPendingList(approverId);
    }
}
