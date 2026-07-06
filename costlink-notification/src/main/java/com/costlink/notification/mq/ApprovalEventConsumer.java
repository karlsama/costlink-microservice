package com.costlink.notification.mq;

import com.costlink.common.mq.MqConstants;
import com.costlink.common.mq.event.ApprovalCompletedEvent;
import com.costlink.common.mq.event.ApprovalNodeCompletedEvent;
import com.costlink.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApprovalEventConsumer {

    private final NotificationService notificationService;

    @RabbitListener(queues = MqConstants.QUEUE_APPROVAL_NODE_COMPLETED)
    public void onNodeCompleted(ApprovalNodeCompletedEvent event) {
        String title = "报销单 #" + event.getReimbursementId();
        String amount = event.getAmount() != null ? event.getAmount().toString() : "0";

        notificationService.sendFromTemplate("APPROVAL_NOTIFY", event.getNextApproverId(),
                Map.of("title", title, "amount", amount),
                "IN_APP", event.getReimbursementId(), "REIMBURSEMENT");

        log.info("审批待办通知已发送, userId={}, reimbursementId={}",
                event.getNextApproverId(), event.getReimbursementId());
    }

    @RabbitListener(queues = MqConstants.QUEUE_APPROVAL_COMPLETED)
    public void onApprovalCompleted(ApprovalCompletedEvent event) {
        if (event == null || event.getReimbursementId() == null || event.getAction() == null) {
            log.warn("审批完成事件字段不完整, 跳过");
            return;
        }

        Long applicantId = event.getApplicantId();
        if (applicantId == null) {
            log.warn("审批完成事件缺少applicantId, 无法发送通知");
            return;
        }

        String templateCode = "APPROVED".equals(event.getAction())
                ? "APPROVAL_APPROVED" : "APPROVAL_REJECTED";
        String title = event.getTitle() != null ? event.getTitle() : "报销单 #" + event.getReimbursementId();
        String amount = event.getAmount() != null ? event.getAmount().toString() : "0";

        notificationService.sendFromTemplate(templateCode, applicantId,
                Map.of("title", title, "amount", amount),
                "IN_APP", event.getReimbursementId(), "REIMBURSEMENT");

        log.info("审批完成通知已发送, userId={}, action={}, reimbursementId={}",
                applicantId, event.getAction(), event.getReimbursementId());
    }
}
