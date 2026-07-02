package com.costlink.approval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {"com.costlink.approval", "com.costlink.common"})
@EnableDiscoveryClient
public class ApprovalApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApprovalApplication.class, args);
    }
}
