package com.costlink.ocr.mq;

import com.costlink.common.mq.MqConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 消费报销单已提交事件 → 对附件做 OCR 识别
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OcrEventConsumer {

    private final ObjectMapper objectMapper;
    private final com.costlink.ocr.service.OcrService ocrService;

    @RabbitListener(queues = MqConstants.QUEUE_REIMBURSEMENT_SUBMITTED)
    public void onReimbursementSubmitted(String message, Channel channel, Message msg) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(message, Map.class);

            Long reimbursementId = body.get("id") != null
                    ? Long.valueOf(body.get("id").toString()) : null;

            if (reimbursementId == null) {
                log.warn("报销单提交事件缺少id, 跳过");
                return;
            }

            // 报销事件的 payload 里可能包含附件列表
            // 目前仅做日志记录，实际附件处理需要报销服务提供附件信息
            log.info("收到报销单提交事件, reimbursementId={}, 等待附件上传后触发OCR", reimbursementId);

        } catch (Exception e) {
            log.error("处理报销单提交事件失败", e);
            throw new RuntimeException(e);
        }
    }
}
