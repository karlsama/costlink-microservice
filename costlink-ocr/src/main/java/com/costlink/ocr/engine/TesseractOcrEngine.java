package com.costlink.ocr.engine;

import com.costlink.common.feign.OcrClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Tesseract OCR 引擎 — 预留，暂未实现
 */
@Component
@ConditionalOnProperty(name = "costlink.ocr.engine", havingValue = "tesseract")
@Slf4j
public class TesseractOcrEngine implements OcrEngine {
    @Override
    public OcrClient.OcrResultDTO recognize(byte[] imageBytes) {
        throw new UnsupportedOperationException("Tesseract OCR 引擎尚未实现");
    }
}
