package com.costlink.notification.mq;

import com.costlink.common.mq.MqConstants;
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
    public void onApprovalCompleted(String message) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(message, Map.class);
            Long reimbursementId = body.get("reimbursementId") != null
                    ? Long.valueOf(body.get("reimbursementId").toString()) : null;
            Long applicantId = body.get("applicantId") != null
                    ? Long.valueOf(body.get("applicantId").toString()) : null;
            String action = (String) body.get("action");

            if (reimbursementId == null || applicantId == null || action == null) {
                log.warn("审批完成事件字段不完整, 跳过");
                return;
            }

            String templateCode = "APPROVED".equals(action)
                    ? "APPROVAL_APPROVED" : "APPROVAL_REJECTED";
            String title = "报销单 #" + reimbursementId;

            notificationService.sendFromTemplate(templateCode, applicantId,
                    Map.of("title", title, "amount",
                            body.get("totalAmount") != null ? body.get("totalAmount").toString() : "0",
                            "reason", body.get("reason") != null ? body.get("reason").toString() : ""),
                    "IN_APP", reimbursementId, "REIMBURSEMENT");

            log.info("审批完成通知已发送, userId={}, action={}, reimbursementId={}",
                    applicantId, action, reimbursementId);

        } catch (Exception e) {
            log.error("处理审批完成事件失败", e);
        }
    }
}
