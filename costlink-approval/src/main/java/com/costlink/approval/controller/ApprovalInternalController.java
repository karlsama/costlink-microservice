package com.costlink.approval.controller;

import com.costlink.approval.service.ApprovalService;
import com.costlink.common.dto.Result;
import com.costlink.common.feign.ApprovalClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/approvals")
@RequiredArgsConstructor
public class ApprovalInternalController {

    private final ApprovalService approvalService;

    @PostMapping("/start")
    public Result<ApprovalClient.StartResponse> start(@RequestBody ApprovalClient.StartRequest request) {
        return approvalService.start(request);
    }
}
