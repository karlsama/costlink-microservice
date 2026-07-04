package com.costlink.approval.mq;

import com.costlink.approval.entity.ApprovalInstance;
import com.costlink.approval.entity.ApprovalNode;

public interface ApprovalEventPublisher {
    void publishNodeCompleted(ApprovalInstance inst, ApprovalNode node);
    void publishApprovalCompleted(ApprovalInstance inst, String action);
}
