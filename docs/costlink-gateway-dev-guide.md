# costlink-gateway 开发指南

> 面向实际开发，一份文档写完 API 网关。不需要回头看其他文件。
> 2026-07-03

---

## 1. 你要做一个什么

**一句话**：所有前端请求的统一入口——门口查 Token，合法就放行并转发到对应微服务，不合法就踢回去。

在微服务体系中，Gateway 是唯一暴露给前端的端口（8080）。前端只知道 `http://localhost:8080`，背后是报销服务还是预算服务，它不关心。

**Gateway 做的事**：

```
前端请求 → Gateway(:8080) → 鉴权 → 路由转发 → 目标微服务
                │
                ├─ /api/auth/login        → costlink-auth:8084    （无需Token）
                ├─ /api/reimbursements/xx → costlink-reimbursement （需Token）
                ├─ /api/budgets/xx        → costlink-budget        （需Token）
                └─ /api/approvals/xx      → costlink-approval      （需Token）
```

**Gateway 不是什么**：不是数据库、不执行业务逻辑、不签发 Token（那是认证服务的事）、不发 MQ 消息。

---

## 2. 你需要提前准备好的东西

**外部依赖（都已就位）**：

| 输入 | 来源 | 状态 |
|-----|------|------|
| JWT 签名密钥 | Nacos 共享配置 `costlink-shared-dev.yaml` 的 `costlink.jwt.secret` | ✅ 已配 |
| Nacos 地址 | bootstrap.yml（默认 `127.0.0.1:8848`） | ✅ 已配 |
| Redis 连接 | Nacos 共享配置 | ✅ 已配 |

**不需要的东西**：

| 不需要 | 原因 |
|-------|------|
| MySQL 连接 | Gateway 不存数据 |
| 百度 OCR Key | Gateway 不识票据 |
| RabbitMQ | Gateway 不参与异步消息 |
| 用户的密码 | 认证服务做登录，Gateway 只看 Token |

---

## 3. 你要写的代码——只有两个文件

Gateway 极其薄。没有 Service、没有 Mapper、没有 Entity、没有 Controller。就两个文件：

```
costlink-gateway/src/main/java/com/costlink/gateway/
├── GatewayApplication.java          ← 启动类（已存在，加一行注解）
└── filter/
    └── JwtAuthGatewayFilterFactory.java   ← 新建：JWT 鉴权过滤器
```

### 3.1 GatewayApplication.java（修改一行）

```java
package com.costlink.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication(scanBasePackages = {"com.costlink.gateway", "com.costlink.common"})
@EnableDiscoveryClient
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

已存在的文件，**不需要修改**——当初骨架生成时已经写对了，`scanBasePackages` 包含了 `com.costlink.common`。

### 3.2 JwtAuthGatewayFilterFactory.java（新建）

这是整个 Gateway 模块唯一需要写的代码。它做的事跟认证服务里的 JWT 过滤器类似，但写法完全不同——Gateway 用的是 WebFlux（响应式，非阻塞），不是 Servlet。

```java
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
 * 重要: Gateway 基于 WebFlux，不能使用 javax.servlet.Filter。
 * 必须用 GatewayFilter + AbstractGatewayFilterFactory。
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

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        };
    }

    /**
     * 配置类 — 不需要字段，只需有 name() 别名让路由配置更简洁
     */
    public static class Config {
    }
}
```

**关键区别**：这块代码不能直接套用认证服务里的 `OncePerRequestFilter`。Gateway 是 WebFlux 架构，没有 `HttpServletRequest`，没有 `FilterChain`。必须用 `AbstractGatewayFilterFactory` + `GatewayFilter`。

### 3.3 JwtUtil 的 Bean 配置

跟认证服务一样，Gateway 也需要注册 `JwtUtil` 为 Spring Bean。由于 Gateway 没有 `SecurityConfig`（Gateway 不依赖 Spring Security），需要单独建一个配置类。

新建文件 `config/GatewayConfig.java`：

```java
package com.costlink.gateway.config;

import com.costlink.common.util.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {

    @Bean
    public JwtUtil jwtUtil(
            @Value("${costlink.jwt.secret}") String secret,
            @Value("${costlink.jwt.access-token-ttl:30}") long accessTokenTtl,
            @Value("${costlink.jwt.refresh-token-ttl:7}") long refreshTokenTtl) {
        return new JwtUtil(secret, accessTokenTtl, refreshTokenTtl);
    }

    /**
     * Redis 限流 Key Resolver（按用户 + IP 限制）
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders()
                    .getFirst("X-User-Id");
            String ip = exchange.getRequest().getRemoteAddress()
                    .getAddress().getHostAddress();
            return reactor.core.publisher.Mono.just(
                    (userId != null ? userId : "anonymous") + ":" + ip
            );
        };
    }
}
```

---

## 4. Nacos 配置——路由表放 Nacos

Gateway 的路由规则也在 Nacos 里，不在本地 application.yml。Nacos 里的配置：

**Data ID**: `costlink-gateway-dev.yaml`

**Group**: `DEFAULT_GROUP`

```yaml
server:
  port: 8080

spring:
  cloud:
    gateway:
      # ========== 路由规则 ==========
      routes:
        # -- 认证服务（无需鉴权，登录/刷新 Token 不用查 Token） --
        - id: costlink-auth
          uri: lb://costlink-auth
          predicates:
            - Path=/api/auth/**
          filters:
            - StripPrefix=0

        # -- 报销服务（需要鉴权 + 限流） --
        - id: costlink-reimbursement
          uri: lb://costlink-reimbursement
          predicates:
            - Path=/api/reimbursements/**
          filters:
            - JwtAuth=true
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 100    # 每秒补充100个令牌
                redis-rate-limiter.burstCapacity: 200     # 最多突发200个请求

        # -- 预算服务 --
        - id: costlink-budget
          uri: lb://costlink-budget
          predicates:
            - Path=/api/budgets/**
          filters:
            - JwtAuth=true

        # -- 审批服务 --
        - id: costlink-approval
          uri: lb://costlink-approval
          predicates:
            - Path=/api/approvals/**
          filters:
            - JwtAuth=true

        # -- OCR 服务（额外限制上传大小 10MB） --
        - id: costlink-ocr
          uri: lb://costlink-ocr
          predicates:
            - Path=/api/ocr/**
          filters:
            - JwtAuth=true
            - name: RequestSize
              args:
                maxSize: 10MB

        # -- 通知服务 --
        - id: costlink-notification
          uri: lb://costlink-notification
          predicates:
            - Path=/api/notifications/**
          filters:
            - JwtAuth=true

        # -- 报表服务 --
        - id: costlink-report
          uri: lb://costlink-report
          predicates:
            - Path=/api/reports/**
          filters:
            - JwtAuth=true

      # 内部接口不对外暴露 — /internal/** 不在路由表
      default-filters:
        - DedupeResponseHeader=Access-Control-Allow-Origin

      discovery:
        locator:
          enabled: true
          lower-case-service-id: true

      # 全局 CORS
      globalcors:
        cors-configurations:
          '[/**]':
            allowedOriginPatterns:
              - "http://localhost:*"
              - "http://127.0.0.1:*"
            allowedMethods: "*"
            allowedHeaders: "*"
            allowCredentials: true
            maxAge: 3600

# Gateway 专用 Redis（限流用，不是共享配置里的那个）
  data:
    redis:
      host: ${REDIS_HOST:127.0.0.1}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}

logging:
  level:
    org.springframework.cloud.gateway: DEBUG
    reactor.netty: INFO
```

注意：`/internal/**` 不在路由表里。前端发 `GET /internal/users/1` 会被 Gateway 直接返回 404，外部无法调内部接口。内部接口只能通过服务间 Feign 调用。

---

## 5. 你不需要写 application.yml

Gateway 已经有一个 `bootstrap.yml`（骨架时生成的，在 `src/main/resources/` 下），指定了 Nacos 地址。配好后不需要改它。Gateway 启动时会：

1. 从 bootstrap.yml 找到 Nacos 地址
2. 从 Nacos 拉 `costlink-shared-dev.yaml`（共享配置，含 JWT 密钥和 Redis）
3. 从 Nacos 拉 `costlink-gateway-dev.yaml`（路由表，上面的内容）
4. 注册到 Nacos（让其他服务知道 Gateway 在哪儿）

---

## 6. 目录结构——最终形态

```
costlink-gateway/src/main/java/com/costlink/gateway/
├── GatewayApplication.java               ← 已存在
├── config/
│   └── GatewayConfig.java                ← 新建：JwtUtil Bean + KeyResolver
└── filter/
    └── JwtAuthGatewayFilterFactory.java  ← 新建：JWT 鉴权过滤器

costlink-gateway/src/main/resources/
└── bootstrap.yml                         ← 已存在
```

---

## 7. 已有的文件——不需动

| 文件 | 位置 | 说明 |
|-----|------|------|
| pom.xml | `costlink-gateway/pom.xml` | 已配好全部依赖（WebFlux、Nacos、LoadBalancer、Redis-Reactive） |
| bootstrap.yml | `src/main/resources/` | Nacos 地址和共享配置引用已正确 |

---

## 8. 编码规范

1. Gateway 没有 Controller、Service、Mapper —— **不要强行加这些东西**。
2. JWT 过滤器必须用 `AbstractGatewayFilterFactory`，不能用 `javax.servlet.Filter`。
3. `JwtUtil` 通过 `@Bean` 注入配置值，跟认证服务的写法一致。
4. 日志必须带关键信息，禁止 `log.info("鉴权成功")`。

---

## 9. 验证方法

**前置**：认证服务必须先跑着（`http://127.0.0.1:8084`），不然 Gateway 转发的请求没人处理。

**启动 Gateway**：

```bash
cd F:\project_007\costlink-gateway
mvn spring-boot:run
```

Gateway 在 `http://127.0.0.1:8080`。

**验证一：登录请求通过了 Gateway 转发到认证服务**

```bash
curl -X POST http://127.0.0.1:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

预期：200 + JWT（请求从 Gateway → 认证服务 → 原路返回）

**验证二：不带 Token 访问需要鉴权的接口 → 拒绝**

```bash
curl http://127.0.0.1:8080/api/reimbursements
```

预期：401（无 Authorization Header，被 Gateway 直接拦截）

**验证三：带 Token 访问被保护的接口 → 放行**

```bash
# 先用登录获取 Token，替换 {TOKEN}
curl http://127.0.0.1:8080/api/reimbursements \
  -H "Authorization: Bearer {TOKEN}"
```

现在报销服务还没写，返回 503（Gateway 知道路由但目标服务不在线）也算通过——说明 Gateway 的鉴权和路由都正确，只是下游没准备好。

**验证四：内部接口被阻断**

```bash
curl http://127.0.0.1:8080/internal/users/1
```

预期：404 —— `/internal/**` 不在路由表，外部请求无法到达。

---

## 10. 检查清单

- [ ] `JwtAuthGatewayFilterFactory` 使用 `AbstractGatewayFilterFactory`（不是 Servlet Filter）
- [ ] `GatewayConfig` 注册了 `JwtUtil` Bean 和 `KeyResolver` Bean
- [ ] 路由配置在 Nacos 中（不在本地 application.yml）
- [ ] `/api/auth/**` 未加 JwtAuth 过滤器
- [ ] `/internal/**` 不在路由表
- [ ] `POST /api/auth/login` 通过 Gateway 返回 200
- [ ] 无 Token 访问受保护路由返回 401
- [ ] 带 Token 访问受保护路由不返回 401（返回 503 也算通过）
- [ ] 所有日志带关键标识符
