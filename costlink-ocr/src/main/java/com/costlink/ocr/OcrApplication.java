package com.costlink.ocr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {"com.costlink.ocr", "com.costlink.common"})
@EnableDiscoveryClient
public class OcrApplication {
    public static void main(String[] args) {
        SpringApplication.run(OcrApplication.class, args);
    }
}
