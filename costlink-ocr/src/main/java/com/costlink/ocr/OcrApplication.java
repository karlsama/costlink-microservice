package com.costlink.ocr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.costlink.ocr", "com.costlink.common"})
@EnableDiscoveryClient
@EnableScheduling  // 定时刷新 AccessToken
public class OcrApplication {
    public static void main(String[] args) {
        SpringApplication.run(OcrApplication.class, args);
    }
}
