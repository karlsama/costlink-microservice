package com.costlink.ocr.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 百度增值税发票识别 API 返回结构
 * 文档: https://ai.baidu.com/ai-doc/OCR/vk3h7y58v
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BaiduOcrResponse {

    private Long logId;
    private List<InvoiceResult> wordsResult;
    private int wordsResultNum;
    private String errorCode;
    private String errorMsg;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InvoiceResult {
        private String words;
    }

    // ========== 结构化提取方法 ==========

    public String getInvoiceCode() {
        return getWord("发票代码");
    }

    public String getInvoiceNumber() {
        return getWord("发票号码");
    }

    public String getInvoiceDate() {
        return getWord("开票日期");
    }

    public BigDecimal getTotalAmount() {
        String val = getWord("价税合计");
        if (val == null) val = getWord("合计金额");
        if (val == null) return null;
        try { return new BigDecimal(val.replaceAll("[^0-9.]", "")); }
        catch (Exception e) { return null; }
    }

    public BigDecimal getTaxAmount() {
        String val = getWord("税额");
        if (val == null) return null;
        try { return new BigDecimal(val.replaceAll("[^0-9.]", "")); }
        catch (Exception e) { return null; }
    }

    public String getSellerName() {
        return getWord("销售方名称");
    }

    public double getConfidence() {
        return 0.95;
    }

    private String getWord(String key) {
        if (wordsResult == null) return null;
        for (InvoiceResult r : wordsResult) {
            if (r.getWords() != null && r.getWords().startsWith(key)) {
                String val = r.getWords().substring(key.length()).trim();
                return val.isEmpty() ? null : val.replace("：", "").trim();
            }
        }
        return null;
    }

    public boolean isSuccess() {
        return errorCode == null || "0".equals(errorCode);
    }
}
