package com.costlink.ocr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 百度 OCR 配置 — 从 Nacos 读取
 */
@Data
@Component
@ConfigurationProperties(prefix = "costlink.ocr.baidu")
public class BaiduOcrProperties {

    private String baseUrl = "https://aip.baidubce.com";
    private String appId;
    private String apiKey;
    private String secretKey;
    private long tokenRefreshBeforeExpire = 86400;
    private int connectTimeout = 5000;
    private int readTimeout = 15000;
    private int dailyFreeQuota = 500;

    /** 图片 Base64 编码后最大长度（默认 4MB 原图 ~= 5.3MB base64） */
    public int getMaxImageBase64Length() {
        return 6_000_000;
    }
}
