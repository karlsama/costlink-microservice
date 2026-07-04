package com.costlink.notification.mq;

import com.costlink.common.mq.MqConstants;
import com.costlink.common.mq.event.BudgetExceededEvent;
import com.costlink.common.mq.event.BudgetFrozenEvent;
import com.costlink.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class BudgetEventConsumer {

    private final NotificationService notificationService;

    @RabbitListener(queues = MqConstants.QUEUE_BUDGET_EXCEEDED)
    public void onBudgetExceeded(BudgetExceededEvent event) {
        if (event.getNotifyUserIds() == null || event.getNotifyUserIds().isEmpty()) {
            log.warn("预算超支事件无通知对象, departmentId={}", event.getDepartmentId());
            return;
        }

        for (Long userId : event.getNotifyUserIds()) {
            notificationService.sendFromTemplate("BUDGET_WARNING", userId,
                    Map.of(
                            "department", event.getDepartmentName() != null ? event.getDepartmentName() : "",
                            "category", event.getCategory() != null ? event.getCategory() : "",
                            "rate", event.getUsedRate() != null ? String.format("%.1f", event.getUsedRate()) : "0",
                            "available", event.getAvailableAmount() != null
                                    ? event.getAvailableAmount().toString() : "0"
                    ),
                    "IN_APP", event.getDepartmentId(), "BUDGET");
        }
        log.info("预算超支通知已发送, departmentId={}, users={}",
                event.getDepartmentId(), event.getNotifyUserIds().size());
    }

    @RabbitListener(queues = MqConstants.QUEUE_BUDGET_FROZEN)
    public void onBudgetFrozen(BudgetFrozenEvent event) {
        String title = "报销单 #" + event.getReimbursementId();

        notificationService.sendFromTemplate("BUDGET_WARNING", event.getApplicantId(),
                Map.of(
                        "title", title,
                        "amount", event.getFrozenAmount() != null
                                ? event.getFrozenAmount().toString() : "0",
                        "available", event.getAvailableAmount() != null
                                ? event.getAvailableAmount().toString() : "0"
                ),
                "IN_APP", event.getReimbursementId(), "REIMBURSEMENT");

        log.info("预算冻结通知已发送, userId={}, reimbursementId={}",
                event.getApplicantId(), event.getReimbursementId());
    }
}
