package com.costlink.ocr.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 百度增值税发票识别 API 返回结构
 * 注意: vat_invoice 接口的字段格式不统一，部分字段直接为字符串，部分为 {"words":"xxx"}
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BaiduOcrResponse {

    @JsonProperty("log_id")
    private Long logId;

    @JsonProperty("words_result")
    private Map<String, Object> wordsResult;

    @JsonProperty("words_result_num")
    private int wordsResultNum;

    @JsonProperty("error_code")
    private String errorCode;

    @JsonProperty("error_msg")
    private String errorMsg;

    public String getInvoiceCode() {
        return getStr("InvoiceCode");
    }

    public String getInvoiceNumber() {
        return getStr("InvoiceNum");
    }

    public String getInvoiceDate() {
        return getStr("InvoiceDate");
    }

    public BigDecimal getTotalAmount() {
        String val = getStr("AmountInFiguers");
        if (val == null) val = getStr("TotalAmount");
        if (val == null) return null;
        try { return new BigDecimal(val.replaceAll("[^0-9.]", "")); }
        catch (Exception e) { return null; }
    }

    public BigDecimal getTaxAmount() {
        String val = getStr("TotalTax");
        if (val == null) return null;
        try { return new BigDecimal(val.replaceAll("[^0-9.]", "")); }
        catch (Exception e) { return null; }
    }

    public String getSellerName() {
        return getStr("SellerName");
    }

    public double getConfidence() {
        return 0.95;
    }

    /**
     * 从 words_result 中提取字符串值，兼容直接字符串和 {"words":"xxx"} 两种格式
     */
    private String getStr(String key) {
        if (wordsResult == null) return null;
        Object val = wordsResult.get(key);
        if (val == null) return null;
        if (val instanceof String) return ((String) val).trim();
        if (val instanceof Map) {
            Object words = ((Map<?, ?>) val).get("words");
            return words != null ? words.toString().trim() : null;
        }
        return val.toString().trim();
    }

    public boolean isSuccess() {
        boolean hasError = errorCode != null && !"0".equals(errorCode);
        return !hasError;
    }
}
