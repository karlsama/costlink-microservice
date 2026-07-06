package com.costlink.approval.controller;

import com.costlink.approval.service.ApprovalService;
import com.costlink.common.dto.Result;
import com.costlink.common.dto.UserContext;
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
        String comment = body.getOrDefault("comment", "");
        return approvalService.approve(instanceId, UserContext.getUserId(), comment);
    }

    @PostMapping("/{instanceId}/reject")
    public Result<?> reject(@PathVariable Long instanceId,
                             @RequestBody Map<String, String> body) {
        String comment = body.getOrDefault("comment", "");
        return approvalService.reject(instanceId, UserContext.getUserId(), comment);
    }

    @PostMapping("/{instanceId}/transfer")
    public Result<?> transfer(@PathVariable Long instanceId,
                               @RequestBody Map<String, Object> body) {
        Long newApproverId = Long.valueOf(body.getOrDefault("newApproverId", "0").toString());
        String comment = (String) body.getOrDefault("comment", "");
        return approvalService.transfer(instanceId, UserContext.getUserId(), newApproverId, comment);
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
