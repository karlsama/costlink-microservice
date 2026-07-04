package com.costlink.ocr.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 百度 AccessToken 管理器
 * 自动获取和定时刷新 Token，缓存在 Redis 中
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AccessTokenManager {

    private static final String TOKEN_KEY = "baidu:ocr:access_token";
    private static final long REFRESH_BEFORE_SEC = 24 * 60 * 60;

    private final StringRedisTemplate redisTemplate;
    private final RestTemplate restTemplate;
    private final BaiduOcrProperties props;

    /** 获取 AccessToken（优先从 Redis 取） */
    public String getAccessToken() {
        String cached = redisTemplate.opsForValue().get(TOKEN_KEY);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        return refreshToken();
    }

    /** 定时刷新 — 每小时检查一次 Token 剩余有效期 */
    @Scheduled(fixedDelay = 3600_000)
    public void refreshIfNeeded() {
        Long ttl = redisTemplate.getExpire(TOKEN_KEY);
        if (ttl == null || ttl < REFRESH_BEFORE_SEC) {
            log.info("AccessToken 即将过期(ttl={}s), 开始刷新", ttl);
            refreshToken();
        }
    }

    /** 从百度 OAuth 接口换取新 Token */
    private String refreshToken() {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            log.warn("百度OCR凭据未配置，无法获取AccessToken");
            return null;
        }

        String url = props.getBaseUrl() + "/oauth/2.0/token"
                + "?grant_type=client_credentials"
                + "&client_id=" + props.getApiKey()
                + "&client_secret=" + props.getSecretKey();

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, HttpEntity.EMPTY, String.class);

            if (response.getBody() == null) {
                log.error("获取AccessToken响应为空");
                return null;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.getBody());
            String token = root.has("access_token") ? root.get("access_token").asText() : null;

            if (token == null) {
                log.error("获取AccessToken失败: {}", response.getBody());
                return null;
            }

            redisTemplate.opsForValue().set(TOKEN_KEY, token, 29, TimeUnit.DAYS);
            log.info("AccessToken 刷新成功");
            return token;

        } catch (Exception e) {
            log.error("获取AccessToken异常", e);
            return null;
        }
    }
}
