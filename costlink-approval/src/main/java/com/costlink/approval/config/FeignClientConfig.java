package com.costlink.approval.config;

import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!mock")
@EnableFeignClients(basePackages = "com.costlink.common.feign")
public class FeignClientConfig {
}
