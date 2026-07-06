package com.costlink.ocr.engine;

import com.costlink.common.feign.OcrClient;
import com.costlink.ocr.config.BaiduOcrProperties;
import com.costlink.ocr.dto.BaiduOcrResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.Map;

/**
 * 百度 OCR 引擎 — 增值税发票识别
 */
@Component
@ConditionalOnProperty(name = "costlink.ocr.engine", havingValue = "baidu", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class BaiduOcrEngine implements OcrEngine {

    private final RestTemplate restTemplate;
    private final BaiduOcrProperties props;
    private final com.costlink.ocr.config.AccessTokenManager tokenManager;

    @Override
    public OcrClient.OcrResultDTO recognize(byte[] imageBytes) {
        // 1. 获取 AccessToken
        String accessToken = tokenManager.getAccessToken();
        if (accessToken == null) {
            return failResult(null, "获取百度AccessToken失败");
        }

        // 2. Base64 编码图片
        String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);
        if (imageBase64.length() > props.getMaxImageBase64Length()) {
            return failResult(null, "图片过大，请压缩后重试");
        }

        // 3. 调百度 API
        String url = props.getBaseUrl() + "/rest/2.0/ocr/v1/vat_invoice?access_token=" + accessToken;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String body = "image=" + java.net.URLEncoder.encode(imageBase64, java.nio.charset.StandardCharsets.UTF_8);

        log.info("调用百度OCR识别, 图片大小={}KB", imageBytes.length / 1024);
        long start = System.currentTimeMillis();

        try {
            ResponseEntity<String> rawResp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class);
            String rawJson = rawResp.getBody();

            BaiduOcrResponse ocrResp = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(rawJson, BaiduOcrResponse.class);
            long elapsed = System.currentTimeMillis() - start;

            if (ocrResp == null || !ocrResp.isSuccess()) {
                String errMsg = ocrResp != null ? ocrResp.getErrorMsg() : "空响应";
                log.warn("百度OCR识别失败, error={}, 耗时={}ms", errMsg, elapsed);
                return failResult(null, errMsg);
            }

            log.info("百度OCR识别成功, 耗时={}ms, 字段数={}", elapsed, ocrResp.getWordsResultNum());
            return toResult(ocrResp);

        } catch (Exception e) {
            log.error("百度OCR调用异常", e);
            return failResult(null, e.getMessage());
        }
    }

    private OcrClient.OcrResultDTO toResult(BaiduOcrResponse ocrResp) {
        OcrClient.OcrResultDTO dto = new OcrClient.OcrResultDTO();
        dto.setStatus("SUCCESS");
        dto.setInvoiceCode(ocrResp.getInvoiceCode());
        dto.setInvoiceNumber(ocrResp.getInvoiceNumber());
        dto.setInvoiceDate(ocrResp.getInvoiceDate());
        dto.setTotalAmount(ocrResp.getTotalAmount());
        dto.setTaxAmount(ocrResp.getTaxAmount());
        dto.setSellerName(ocrResp.getSellerName());
        dto.setConfidence(ocrResp.getConfidence());
        return dto;
    }

    private OcrClient.OcrResultDTO failResult(Long attachmentId, String errorMessage) {
        OcrClient.OcrResultDTO dto = new OcrClient.OcrResultDTO();
        dto.setAttachmentId(attachmentId);
        dto.setStatus("FAILED");
        dto.setErrorMessage(errorMessage);
        return dto;
    }
}
