package com.costlink.ocr.mq;

import com.costlink.common.mq.MqConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OcrEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    /** 发布 OCR 完成事件 */
    public void publishOcrCompleted(Long attachmentId, Long reimbursementId,
                                     BigDecimal ocrAmount, String ocrResult) {
        try {
            Map<String, Object> body = Map.of(
                    "attachmentId", attachmentId,
                    "reimbursementId", reimbursementId,
                    "ocrAmount", ocrAmount,
                    "ocrResult", ocrResult);
            rabbitTemplate.convertAndSend(
                    MqConstants.EXCHANGE_OCR,
                    MqConstants.RK_OCR_COMPLETED,
                    body);
            log.info("发布OCR完成事件, attachmentId={}, reimbursementId={}", attachmentId, reimbursementId);
        } catch (Exception e) {
            log.warn("发布OCR完成事件失败(MQ不可用), attachmentId={}", attachmentId, e.getMessage());
        }
    }

    /** 发布 OCR 失败事件 */
    public void publishOcrFailed(Long attachmentId, Long reimbursementId, String errorMessage) {
        try {
            Map<String, Object> body = Map.of(
                    "attachmentId", attachmentId,
                    "reimbursementId", reimbursementId,
                    "errorMessage", errorMessage);
            rabbitTemplate.convertAndSend(
                    MqConstants.EXCHANGE_OCR,
                    MqConstants.RK_OCR_FAILED,
                    body);
            log.info("发布OCR失败事件, attachmentId={}, reimbursementId={}", attachmentId, reimbursementId);
        } catch (Exception e) {
            log.warn("发布OCR失败事件异常(MQ不可用), attachmentId={}", attachmentId, e.getMessage());
        }
    }
}
