package com.costlink.approval.service;

import com.costlink.approval.entity.ApprovalInstance;
import com.costlink.approval.entity.ApprovalNode;
import com.costlink.common.dto.Result;
import com.costlink.common.feign.ApprovalClient;

public interface ApprovalService {
    // 内部 Feign 接口
    Result<ApprovalClient.StartResponse> start(ApprovalClient.StartRequest request);

    // 对外接口
    Result<?> approve(Long instanceId, Long operatorId, String comment);
    Result<?> reject(Long instanceId, Long operatorId, String comment);
    Result<?> transfer(Long instanceId, Long operatorId, Long newApproverId, String comment);
    Result<?> getPending(Long approverId);
    Result<ApprovalInstance> getInstanceDetail(Long instanceId);
}
