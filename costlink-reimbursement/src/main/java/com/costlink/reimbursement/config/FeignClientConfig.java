package com.costlink.reimbursement.config;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Feign 客户端配置 — 非 mock 模式时激活
 */
@Configuration
@Profile("!mock")
@EnableFeignClients(basePackages = "com.costlink.common.feign")
public class FeignClientConfig {
}
