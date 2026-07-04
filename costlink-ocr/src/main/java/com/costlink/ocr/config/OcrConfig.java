package com.costlink.ocr.config;

import com.costlink.common.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class OcrConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public JwtUtil jwtUtil(
            @Value("${costlink.jwt.secret}") String secret,
            @Value("${costlink.jwt.access-token-ttl:30}") long accessTokenTtl,
            @Value("${costlink.jwt.refresh-token-ttl:7}") long refreshTokenTtl) {
        return new JwtUtil(secret, accessTokenTtl, refreshTokenTtl);
    }
}
