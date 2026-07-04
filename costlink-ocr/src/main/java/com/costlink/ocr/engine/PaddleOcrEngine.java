package com.costlink.ocr.engine;

import com.costlink.common.feign.OcrClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * PaddleOCR 引擎 — 预留，暂未实现
 */
@Component
@ConditionalOnProperty(name = "costlink.ocr.engine", havingValue = "paddle")
@Slf4j
public class PaddleOcrEngine implements OcrEngine {
    @Override
    public OcrClient.OcrResultDTO recognize(byte[] imageBytes) {
        throw new UnsupportedOperationException("PaddleOCR 引擎尚未实现");
    }
}
