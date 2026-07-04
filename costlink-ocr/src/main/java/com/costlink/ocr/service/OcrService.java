package com.costlink.ocr.service;

import com.costlink.common.feign.OcrClient;
import com.costlink.ocr.engine.OcrEngine;
import com.costlink.ocr.mq.OcrEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * OCR 服务 — 含缓存查询 + 识别调度
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OcrService {

    private final StringRedisTemplate redisTemplate;
    private final OcrEngine ocrEngine;
    private final OcrEventPublisher eventPublisher;

    /** 同步识别 */
    public OcrClient.OcrResultDTO recognize(Long attachmentId, String fileHash, byte[] imageBytes) {
        // 1. MD5 缓存检查
        if (fileHash != null) {
            String cached = redisTemplate.opsForValue().get("ocr:result:" + fileHash);
            if (cached != null) {
                log.info("缓存命中(MD5), attachmentId={}, fileHash={}", attachmentId, fileHash);
                OcrClient.OcrResultDTO dto = fromJson(cached);
                if (dto != null) return dto;
            }
        }

        // 2. 调 OCR 引擎
        OcrClient.OcrResultDTO result = ocrEngine.recognize(imageBytes);
        result.setAttachmentId(attachmentId);

        // 3. 写缓存（成功才缓存）
        if ("SUCCESS".equals(result.getStatus()) && fileHash != null) {
            redisTemplate.opsForValue().set(
                    "ocr:result:" + fileHash, toJson(result), 24, TimeUnit.HOURS);

            if (result.getInvoiceNumber() != null) {
                redisTemplate.opsForValue().set(
                        "ocr:invoice:" + result.getInvoiceNumber(), fileHash);
            }
        }

        return result;
    }

    /** 异步识别 */
    public void recognizeAsync(Long attachmentId, Long reimbursementId, String fileHash, byte[] imageBytes) {
        OcrClient.OcrResultDTO result = recognize(attachmentId, fileHash, imageBytes);

        if ("SUCCESS".equals(result.getStatus())) {
            eventPublisher.publishOcrCompleted(attachmentId, reimbursementId,
                    result.getTotalAmount(), toJson(result));
            log.info("异步识别完成, attachmentId={}, reimbursementId={}", attachmentId, reimbursementId);
        } else {
            eventPublisher.publishOcrFailed(attachmentId, reimbursementId, result.getErrorMessage());
            log.warn("异步识别失败, attachmentId={}, reimbursementId={}, error={}",
                    attachmentId, reimbursementId, result.getErrorMessage());
        }
    }

    /** 查询缓存结果 */
    public OcrClient.OcrResultDTO getResult(Long attachmentId, String fileHash) {
        if (fileHash == null) return null;
        String cached = redisTemplate.opsForValue().get("ocr:result:" + fileHash);
        if (cached == null) return null;
        OcrClient.OcrResultDTO dto = fromJson(cached);
        if (dto != null) dto.setAttachmentId(attachmentId);
        return dto;
    }

    private String toJson(OcrClient.OcrResultDTO dto) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto);
        } catch (Exception e) {
            return null;
        }
    }

    private OcrClient.OcrResultDTO fromJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    json, OcrClient.OcrResultDTO.class);
        } catch (Exception e) {
            return null;
        }
    }
}
