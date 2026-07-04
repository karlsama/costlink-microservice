package com.costlink.reimbursement.mq;

import com.costlink.common.mq.MqConstants;
import com.costlink.reimbursement.entity.Reimbursement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReimbursementEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishReimbursementSubmitted(Reimbursement r) {
        rabbitTemplate.convertAndSend(
                MqConstants.EXCHANGE_REIMBURSEMENT,
                MqConstants.RK_REIMBURSEMENT_SUBMITTED,
                r);
        log.info("发布报销单已提交事件, reimbursementId={}", r.getId());
    }

    public void publishReimbursementApproved(Reimbursement r) {
        rabbitTemplate.convertAndSend(
                MqConstants.EXCHANGE_REIMBURSEMENT,
                MqConstants.RK_REIMBURSEMENT_APPROVED,
                r);
        log.info("发布报销单已通过事件, reimbursementId={}", r.getId());
    }

    public void publishReimbursementRejected(Reimbursement r) {
        rabbitTemplate.convertAndSend(
                MqConstants.EXCHANGE_REIMBURSEMENT,
                MqConstants.RK_REIMBURSEMENT_REJECTED,
                r);
        log.info("发布报销单已驳回事件, reimbursementId={}", r.getId());
    }

    public void publishReimbursementPaid(Reimbursement r) {
        rabbitTemplate.convertAndSend(
                MqConstants.EXCHANGE_REIMBURSEMENT,
                MqConstants.RK_REIMBURSEMENT_PAID,
                r);
        log.info("发布报销单已付款事件, reimbursementId={}", r.getId());
    }
}
