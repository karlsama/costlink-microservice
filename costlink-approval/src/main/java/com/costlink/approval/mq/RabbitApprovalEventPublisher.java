package com.costlink.approval.mq;

import com.costlink.approval.entity.ApprovalInstance;
import com.costlink.approval.entity.ApprovalNode;
import com.costlink.common.mq.MqConstants;
import com.costlink.common.mq.event.ApprovalCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Profile("!mock")
@Primary
@RequiredArgsConstructor
@Slf4j
public class RabbitApprovalEventPublisher implements ApprovalEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publishNodeCompleted(ApprovalInstance inst, ApprovalNode node) {
        com.costlink.common.mq.event.ApprovalNodeCompletedEvent event =
                new com.costlink.common.mq.event.ApprovalNodeCompletedEvent(
                        inst.getReimbursementId(), inst.getId(),
                        "报销单 #" + inst.getReimbursementId(),
                        inst.getTotalAmount(),
                        node.getApproverId(), node.getApproverName());
        rabbitTemplate.convertAndSend(
                MqConstants.EXCHANGE_APPROVAL,
                MqConstants.RK_APPROVAL_NODE_COMPLETED,
                event);
        log.info("发布节点完成事件, instanceId={}, nodeOrder={}", inst.getId(), node.getNodeOrder());
    }

    @Override
    public void publishApprovalCompleted(ApprovalInstance inst, String action) {
        ApprovalCompletedEvent event = new ApprovalCompletedEvent(
                inst.getReimbursementId(), inst.getId(), action,
                "报销单 #" + inst.getReimbursementId(),
                inst.getTotalAmount(), inst.getApplicantId());
        rabbitTemplate.convertAndSend(
                MqConstants.EXCHANGE_APPROVAL,
                MqConstants.RK_APPROVAL_COMPLETED,
                event);
        log.info("发布审批完成事件, instanceId={}, action={}, reimbursementId={}",
                inst.getId(), action, inst.getReimbursementId());
    }
}
