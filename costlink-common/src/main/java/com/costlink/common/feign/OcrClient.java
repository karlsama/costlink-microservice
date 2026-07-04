package com.costlink.common.feign;

import com.costlink.common.dto.Result;
import lombok.Data;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

/**
 * Feign 接口 — OCR 服务（报销服务调用）
 */
@FeignClient(name = "costlink-ocr", path = "/internal/ocr")
public interface OcrClient {

    /** 识别票据（异步模式 — OCR 服务处理后通过 MQ 回写结果） */
    @PostMapping("/recognize-async")
    Result<Void> recognizeAsync(@RequestBody RecognizeRequest request);

    /** 识别票据（同步模式 — 等待结果返回，用于实时预览） */
    @PostMapping("/recognize")
    Result<OcrResultDTO> recognize(@RequestBody RecognizeRequest request);

    /** 查询识别结果 */
    @PostMapping("/result")
    Result<OcrResultDTO> getResult(@RequestParam Long attachmentId);

    // ---------- DTOs ----------

    @Data
    class RecognizeRequest {
        private Long attachmentId;
        private Long reimbursementId;
        private String base64Image;    // 图片 Base64 编码（OCR 服务直接用，不靠文件路径）
        private String fileHash;       // MD5，用于缓存去重
    }

    @Data
    class OcrResultDTO {
        private Long attachmentId;
        private String status;           // SUCCESS / FAILED / PROCESSING
        private String invoiceCode;      // 发票代码
        private String invoiceNumber;    // 发票号码
        private String invoiceDate;      // 开票日期
        private BigDecimal totalAmount;  // 价税合计
        private BigDecimal taxAmount;    // 税额
        private String sellerName;       // 销方名称
        private Double confidence;       // 置信度 0-1
        private String errorMessage;
    }
}
