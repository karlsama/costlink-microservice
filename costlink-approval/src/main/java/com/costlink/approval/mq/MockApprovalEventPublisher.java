package com.costlink.approval.mq;

import com.costlink.approval.entity.ApprovalInstance;
import com.costlink.approval.entity.ApprovalNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("mock")
@Slf4j
public class MockApprovalEventPublisher implements ApprovalEventPublisher {

    @Override
    public void publishNodeCompleted(ApprovalInstance inst, ApprovalNode node) {
        log.info("[Mock] 发布节点完成事件, instanceId={}, nodeOrder={}, approverId={}",
                inst.getId(), node.getNodeOrder(), node.getApproverId());
    }

    @Override
    public void publishApprovalCompleted(ApprovalInstance inst, String action) {
        log.info("[Mock] 发布审批完成事件, instanceId={}, action={}, reimbursementId={}",
                inst.getId(), action, inst.getReimbursementId());
    }
}
