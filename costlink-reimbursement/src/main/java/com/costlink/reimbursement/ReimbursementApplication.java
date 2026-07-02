package com.costlink.reimbursement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {"com.costlink.reimbursement", "com.costlink.common"})
@EnableDiscoveryClient
public class ReimbursementApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReimbursementApplication.class, args);
    }
}
