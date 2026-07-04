package com.costlink.reimbursement.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.costlink.common.mq.MqConstants;
import com.costlink.reimbursement.entity.OutboxMessage;
import com.costlink.reimbursement.mapper.OutboxMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxScheduledTask {

    private final OutboxMessageMapper outboxMessageMapper;
    private final RabbitTemplate rabbitTemplate;

    @Scheduled(fixedDelay = 5000)
    public void processOutbox() {
        List<OutboxMessage> messages = outboxMessageMapper.selectList(
                new LambdaQueryWrapper<OutboxMessage>()
                        .eq(OutboxMessage::getStatus, "READY")
                        .last("LIMIT 50"));

        for (OutboxMessage msg : messages) {
            try {
                rabbitTemplate.convertAndSend(
                        MqConstants.EXCHANGE_REIMBURSEMENT,
                        msg.getEventType(),
                        msg.getPayload());
                msg.setStatus("SENT");
                msg.setSentTime(LocalDateTime.now());
                outboxMessageMapper.updateById(msg);
                log.debug("Outbox消息发送成功, messageId={}, eventType={}", msg.getMessageId(), msg.getEventType());
            } catch (Exception e) {
                msg.setRetryCount(msg.getRetryCount() == null ? 1 : msg.getRetryCount() + 1);
                msg.setErrorMessage(e.getMessage());
                outboxMessageMapper.updateById(msg);
                log.warn("Outbox消息发送失败, messageId={}, retry={}", msg.getMessageId(), msg.getRetryCount());
            }
        }
    }
}
