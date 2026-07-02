package com.costlink.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类 — 认证服务和 Gateway 共用
 */
@Slf4j
public class JwtUtil {

    private final String secret;
    private final long accessTokenTtl;   // 分钟
    private final long refreshTokenTtl;  // 天

    public JwtUtil(String secret, long accessTokenTtl, long refreshTokenTtl) {
        this.secret = secret;
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    private SecretKey getSigningKey() {
        // HMAC-SHA256 要求密钥至少 256 位（32 字节）
        // 简单做法：直接用 secret 的 UTF-8 字节作为密钥
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    public String generateAccessToken(Long userId, String role, Long departmentId) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + accessTokenTtl * 60 * 1000);

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("role", role);
        claims.put("departmentId", departmentId);

        return Jwts.builder()
            .claims(claims)
            .subject(String.valueOf(userId))
            .issuedAt(now)
            .expiration(expiration)
            .signWith(getSigningKey())
            .compact();
    }

    public String generateRefreshToken(Long userId) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + refreshTokenTtl * 24 * 60 * 60 * 1000);

        return Jwts.builder()
            .subject(String.valueOf(userId))
            .issuedAt(now)
            .expiration(expiration)
            .signWith(getSigningKey())
            .compact();
    }

    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        } catch (JwtException e) {
            log.warn("JWT解析失败: {}", e.getMessage());
            throw e;
        }
    }

    public long getAccessTokenTtl() {
        return accessTokenTtl * 60;  // 返回秒
    }

    public long getRefreshTokenTtl() {
        return refreshTokenTtl;
    }
}
