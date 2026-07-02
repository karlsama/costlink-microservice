package com.costlink.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

import org.mybatis.spring.annotation.MapperScan;

@SpringBootApplication(scanBasePackages = {"com.costlink.auth", "com.costlink.common"})
@EnableDiscoveryClient
@MapperScan("com.costlink.auth.mapper")
public class AuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
