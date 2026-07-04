package com.costlink.approval.controller;

import com.costlink.approval.service.ApprovalService;
import com.costlink.common.dto.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    @PostMapping("/{instanceId}/approve")
    public Result<?> approve(@PathVariable Long instanceId,
                              @RequestBody Map<String, String> body) {
        Long operatorId = Long.valueOf(body.getOrDefault("operatorId", "0"));
        String comment = body.getOrDefault("comment", "");
        return approvalService.approve(instanceId, operatorId, comment);
    }

    @PostMapping("/{instanceId}/reject")
    public Result<?> reject(@PathVariable Long instanceId,
                             @RequestBody Map<String, String> body) {
        Long operatorId = Long.valueOf(body.getOrDefault("operatorId", "0"));
        String comment = body.getOrDefault("comment", "");
        return approvalService.reject(instanceId, operatorId, comment);
    }

    @PostMapping("/{instanceId}/transfer")
    public Result<?> transfer(@PathVariable Long instanceId,
                               @RequestBody Map<String, Object> body) {
        Long operatorId = Long.valueOf(body.getOrDefault("operatorId", "0").toString());
        Long newApproverId = Long.valueOf(body.getOrDefault("newApproverId", "0").toString());
        String comment = (String) body.getOrDefault("comment", "");
        return approvalService.transfer(instanceId, operatorId, newApproverId, comment);
    }

    @GetMapping("/pending")
    public Result<?> getPending(@RequestParam Long approverId) {
        return approvalService.getPending(approverId);
    }

    @GetMapping("/instances/{id}")
    public Result<?> getInstanceDetail(@PathVariable Long id) {
        return approvalService.getInstanceDetail(id);
    }
}
