package com.costlink.gateway.filter;

import com.costlink.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/**
 * JWT 鉴权过滤器工厂 — 在路由配置中用 "JwtAuth=true" 激活
 *
 * Gateway 基于 WebFlux，必须用 AbstractGatewayFilterFactory + GatewayFilter，
 * 不能使用 javax.servlet.Filter。
 */
@Slf4j
@Component
public class JwtAuthGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JwtAuthGatewayFilterFactory.Config> {

    private final JwtUtil jwtUtil;

    public JwtAuthGatewayFilterFactory(JwtUtil jwtUtil) {
        super(Config.class);
        this.jwtUtil = jwtUtil;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String authHeader = exchange.getRequest()
                    .getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            // 1. 检查 Authorization Header
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("缺少或无效的Authorization Header");
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            // 2. 解析 JWT
            String token = authHeader.substring(7);
            Claims claims;
            try {
                claims = jwtUtil.parseToken(token);
            } catch (JwtException e) {
                log.warn("JWT解析失败: {}", e.getMessage());
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            // 3. 注入用户信息到下游 Header
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header("X-User-Id", String.valueOf(claims.get("userId")))
                    .header("X-User-Role", (String) claims.get("role"))
                    .header("X-Department-Id",
                            claims.get("departmentId") != null
                                ? String.valueOf(claims.get("departmentId"))
                                : "0")
                    .build();

            log.debug("JWT鉴权通过, userId={}, role={}", claims.get("userId"), claims.get("role"));
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        };
    }

    public static class Config {
    }
}
