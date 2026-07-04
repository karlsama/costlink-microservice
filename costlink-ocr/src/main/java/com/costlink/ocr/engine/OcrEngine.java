package com.costlink.ocr.engine;

import com.costlink.common.feign.OcrClient;

/**
 * OCR 引擎接口（策略模式）
 */
public interface OcrEngine {

    OcrClient.OcrResultDTO recognize(byte[] imageBytes);
}
