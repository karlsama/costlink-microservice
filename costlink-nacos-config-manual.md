# CostLink Nacos 配置管理手册

> **配套文档**: costlink-microservice-framework.md（架构框架）
> **适用环境**: Nacos + MySQL 在 Windows 本机，Docker 在 WSL 中运行微服务容器
> **版本**: v1.0
> **日期**: 2026-07-01

---

## 目录

1. [网络拓扑与连接方案](#1-网络拓扑与连接方案)
2. [Nacos Server 安装与 MySQL 配置](#2-nacos-server-安装与-mysql-配置)
3. [Namespace 规划](#3-namespace-规划)
4. [配置层级设计](#4-配置层级设计)
5. [共享配置](#5-共享配置)
6. [网关服务配置](#6-网关服务配置)
7. [认证服务配置](#7-认证服务配置)
8. [报销服务配置](#8-报销服务配置)
9. [预算服务配置](#9-预算服务配置)
10. [审批服务配置](#10-审批服务配置)
11. [OCR 服务配置](#11-ocr-服务配置)
12. [通知服务配置](#12-通知服务配置)
13. [报表服务配置](#13-报表服务配置)
14. [Docker Compose 适配](#14-docker-compose-适配)
15. [配置热刷新与运维](#15-配置热刷新与运维)

---

## 1. 网络拓扑与连接方案

### 1.1 实际部署关系

```
┌──────────────────────────────────────────────────────────────┐
│  Windows 本机 (127.0.0.1)                                     │
│                                                               │
│  ┌─────────────────┐        ┌─────────────────┐              │
│  │  MySQL 8.0      │        │  Nacos Server   │              │
│  │  127.0.0.1:3306 │        │  127.0.0.1:8848 │              │
│  │                 │        │  (使用MySQL存储) │              │
│  └────────┬────────┘        └────────┬────────┘              │
│           │                          │                        │
│           └──────────┬───────────────┘                        │
│                      │                                        │
│  ────────────────────┼── WSL 镜像网络边界 ──────────────────  │
│                      │                                        │
│  ┌───────────────────┴────────────────────────────────────┐  │
│  │  WSL (127.0.0.1 与 Windows 已打通)                     │  │
│  │                                                        │  │
│  │  ┌──────────────────────────────────────────────────┐  │  │
│  │  │  Docker Engine                                  │  │  │
│  │  │                                                  │  │  │
│  │  │  ┌──────────┐ ┌──────────┐ ┌──────────┐        │  │  │
│  │  │  │ 报销服务  │ │ 预算服务  │ │ 审批服务  │  ...   │  │  │
│  │  │  │ :8081    │ │ :8082    │ │ :8083    │        │  │  │
│  │  │  └──────────┘ └──────────┘ └──────────┘        │  │  │
│  │  │                                                  │  │  │
│  │  │  Nacos (host 网络模式)                            │  │  │
│  │  │  ├─ 共享宿主机 127.0.0.1:8848                    │  │  │
│  │  │  ├─ 访问 MySQL: 127.0.0.1:3306 (Windows)         │  │  │
│  │  │  └─ 访问 Redis/RabbitMQ: 容器名 (bridge 网络)     │  │  │
│  │  │                                                  │  │  │
│  │  │  微服务容器(bridge 网络)                           │  │  │
│  │  │  ├─ 访问 Redis: redis:6379                       │  │  │
│  │  │  ├─ 访问 RabbitMQ: rabbitmq:5672                 │  │  │
│  │  │  └─ 访问 Nacos: 开发阶段 IDE 直连 127.0.0.1:8848 │  │  │
│  │  │      (bridge 容器无法穿透到 Windows 端口)          │  │  │
│  │  └──────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

### 1.2 连接规则（实际验证通过的版本）

| 谁访问谁 | 地址写法 | 说明 |
|---------|---------|------|
| **Nacos → MySQL** | `127.0.0.1:3306` | Nacos 用 host 网络模式，共享宿主机网络栈，直连 Windows MySQL |
| **IDE 起的服务 → Nacos** | `127.0.0.1:8848` | 服务跑在 Windows 上，Nacos 在 Docker host 网络 8848 端口 |
| **IDE 起的服务 → MySQL** | `127.0.0.1:3306` | 同在 Windows |
| **Docker 容器 → Redis/RabbitMQ** | `redis:6379` / `rabbitmq:5672` | 同在 bridge 网络，容器名互访 |
| **Docker bridge 容器 → Windows** | ❌ 不通 | Docker Desktop WSL2 限制，bridge 容器无法访问宿主端口 |

> 开发阶段微服务在 IDE 跑（非 Docker），所有 `127.0.0.1` 直连。集成阶段的 bridge 容器访问 Windows 问题后续单独解决。

**关键点**: Docker 容器内部写 `127.0.0.1` 指向的是容器自己，不是 Windows。所以所有需要访问 Windows 上服务的容器，一律用 `host.docker.internal`。这个域名是 Docker Desktop 自动注入的，无需额外配置。

---

## 2. Nacos Server 安装与 MySQL 配置

### 2.1 下载与解压

在 Windows 上下载 Nacos：

```
下载地址: https://github.com/alibaba/nacos/releases
推荐版本: nacos-server-2.3.2.zip（稳定版，Spring Cloud 2023.0.x 兼容）
```

解压到 `D:\nacos`（示例路径，你按实际调整）。

### 2.2 初始化 Nacos 的 MySQL 数据库

Nacos 默认用嵌入式 Derby 存配置，这只能在单机用且重启丢数据。我们要让它用 MySQL。

先在 MySQL 中建库：

```sql
-- 用 MySQL 客户端（Navicat / DBeaver / 命令行）连 127.0.0.1:3306 执行
CREATE DATABASE nacos_config
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_bin;
```

然后导入 Nacos 自带的初始化 SQL：

```
用 MySQL 客户端执行这个文件:
D:\nacos\conf\mysql-schema.sql
```

它会自动在 `nacos_config` 库中创建 Nacos 所需的十几张表（config_info、users、roles 等）。

### 2.3 修改 Nacos 配置

编辑 `D:\nacos\conf\application.properties`：

```properties
# ========== Nacos 核心配置 ==========

# 使用 MySQL 存储（而非内嵌 Derby）
spring.sql.init.platform=mysql

# MySQL 连接信息 — Nacos 和 MySQL 都在 Windows 本机，直接用 127.0.0.1
db.num=1
db.url.0=jdbc:mysql://127.0.0.1:3306/nacos_config?characterEncoding=utf8&connectTimeout=1000&socketTimeout=3000&autoReconnect=true&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
db.user.0=root
db.password.0=你的MySQL密码

# 如果 MySQL 8.0 需要指定驱动
db.pool.config.driverClassName=com.mysql.cj.jdbc.Driver

# ========== 鉴权（生产环境建议开启） ==========
nacos.core.auth.enabled=false
nacos.core.auth.server.identity.key=serverIdentity
nacos.core.auth.server.identity.value=security
# 开发阶段先关掉，等熟悉了 Nacos 再开
```

### 2.4 启动 Nacos

```powershell
# 在 Windows 终端中，进入 Nacos 目录
cd D:\nacos

# standalone 模式启动（单机模式，开发够用）
bin\startup.cmd -m standalone
```

启动成功后访问 `http://127.0.0.1:8848/nacos`，默认用户名密码都是 `nacos`。

---

## 3. Namespace 规划

### 3.1 概念解释

Namespace 是 Nacos 中最顶层的隔离单元。不同 Namespace 之间的配置完全不互通，各自独立。

你可以把它理解为"三套完全独立的 Nacos"——但实际只需要部署一个 Nacos Server 实例，靠 Namespace ID 区分环境。

### 3.2 创建 Namespace

登录 Nacos 控制台 → 左侧菜单「命名空间」→ 新建命名空间：

| 命名空间名 | 命名空间ID (自动生成，记录备用) | 用途 |
|-----------|-------------------------------|------|
| `costlink-dev` | 记录控制台显示的 ID | 本地 Docker Compose 开发 |
| `costlink-test` | 记录控制台显示的 ID | 测试服务器 |
| `costlink-prod` | 记录控制台显示的 ID | 生产环境 |

创建后你会得到类似这样的 ID（实际值不同，因为 Nacos 自动生成 UUID）：

- `costlink-dev`: `a1b2c3d4-e5f6-7890-abcd-ef1234567890`
- `costlink-test`: `b2c3d4e5-f6a7-8901-bcde-f12345678901`
- `costlink-prod`: `c3d4e5f6-a7b8-9012-cdef-123456789012`

### 3.3 微服务如何指定 Namespace

每个微服务的 `bootstrap.yml`（或 `application.yml`）中通过 `namespace` 字段指定自己的环境：

```yaml
spring:
  cloud:
    nacos:
      config:
        namespace: a1b2c3d4-e5f6-7890-abcd-ef1234567890  # dev
```

这个值决定了该服务从哪个 Namespace 拉取配置。也就是说，**dev 环境部署的服务写 dev 的 ID，prod 环境部署的服务写 prod 的 ID**，同一个服务镜像可以配不同 ID 部署到不同环境，实现"一套代码，多套配置"。

---

## 4. 配置层级设计

### 4.1 三层配置模型

一个微服务启动时，Nacos 会按以下顺序加载配置（后加载的覆盖先加载的）：

```
Layer 1: 共享配置 (shared-configs)
         └─ 所有服务通用的配置（JWT密钥、Redis、RabbitMQ等）
              ↓ 后加载的覆盖先加载的
Layer 2: 扩展配置 (extension-configs)
         └─ 部分服务共享的配置（如 Feign 超时、Sentinel 阈值）
              ↓ 后加载的覆盖先加载的
Layer 3: 服务独有配置
         └─ 每个服务自己的 Data ID（数据库连接、服务端口等）
              ↓ 最终生效的配置
         spring.application.name + profiles + file-extension
```

### 4.2 Data ID 命名规则

Nacos 中每个配置文件由一个 **Data ID** 唯一标识。命名遵循以下规范：

```
{prefix}-{spring.profiles.active}.{file-extension}

示例:
  costlink-reimbursement-dev.yaml    ← 报销服务在 dev 环境的配置
  costlink-budget-dev.yaml           ← 预算服务在 dev 环境的配置
  costlink-shared-dev.yaml           ← dev 环境所有服务共享的配置
```

### 4.3 配置拆分全景

```
Nacos Namespace: costlink-dev
│
├── costlink-shared-dev.yaml          ← 所有服务共享
│
├── costlink-gateway-dev.yaml         ← 网关独有
├── costlink-auth-dev.yaml            ← 认证服务独有
├── costlink-reimbursement-dev.yaml   ← 报销服务独有（可热刷）
├── costlink-budget-dev.yaml          ← 预算服务独有（可热刷）
├── costlink-approval-dev.yaml        ← 审批服务独有（可热刷）
├── costlink-ocr-dev.yaml             ← OCR 服务独有
├── costlink-notification-dev.yaml    ← 通知服务独有
└── costlink-report-dev.yaml          ← 报表服务独有
```

### 4.4 微服务 bootstrap.yml 统一模板

每个微服务需要在自己的 `src/main/resources/bootstrap.yml` 中告诉 Nacos：我在哪个 Namespace、我要拉哪些共享配置、我的服务名是什么。

```yaml
# bootstrap.yml — 每个微服务都有一份（内容几乎相同，仅服务名不同）
spring:
  application:
    name: costlink-reimbursement   # ← 改成自己的服务名

  cloud:
    nacos:
      # 服务发现
      discovery:
        server-addr: ${NACOS_SERVER_ADDR:host.docker.internal:8848}
        namespace: ${NACOS_NAMESPACE:a1b2c3d4-e5f6-7890-abcd-ef1234567890}
        group: DEFAULT_GROUP
        # 注册时带上本机IP（容器内 127.0.0.1 不能用）
        ip: ${HOST_IP:}
        # 元数据，方便网关按元数据路由
        metadata:
          version: 1.0

      # 配置管理
      config:
        server-addr: ${NACOS_SERVER_ADDR:host.docker.internal:8848}
        namespace: ${NACOS_NAMESPACE:a1b2c3d4-e5f6-7890-abcd-ef1234567890}
        group: DEFAULT_GROUP
        file-extension: yaml
        # 启动超时（Nacos 连接慢时不至于启动失败）
        timeout: 5000

        # -- Layer 1: 共享配置 --
        shared-configs:
          - data-id: costlink-shared-${spring.profiles.active}.yaml
            group: DEFAULT_GROUP
            refresh: true    # 支持热刷新

        # -- Layer 2: 扩展配置（按需） --
        extension-configs:
          - data-id: costlink-feign-common-${spring.profiles.active}.yaml
            group: DEFAULT_GROUP
            refresh: true

  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}  # 环境变量控制，默认 dev

# 暴露 Actuator 端点（Prometheus 采集和健康检查用）
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: always
```

这个模板是每个服务共用的。唯一要改的只有第一行 `spring.application.name`。剩余差异通过环境变量注入（下面会详细说明）。

---

## 5. 共享配置

### 5.1 Data ID: `costlink-shared-dev.yaml`

这份配置会被所有 8 个服务加载，放在 Nacos 的 `costlink-dev` Namespace 下。

```yaml
# ============================================================
# Data ID: costlink-shared-dev.yaml
# Group:   DEFAULT_GROUP
# 说明:    dev 环境所有微服务共享的基础配置
# ============================================================

# ========== JWT 配置 ==========
costlink:
  jwt:
    # HMAC-SHA256 签名密钥，至少256位。生成方法见下方说明
    secret: ${JWT_SECRET:CostLink-JWT-Secret-Key-2026-For-Dev-Environment-Only-Do-Not-Use-In-Production}
    # Access Token 有效期（分钟）
    access-token-ttl: 30
    # Refresh Token 有效期（天）
    refresh-token-ttl: 7
    # Token 签发者
    issuer: costlink

  # ========== 加密密钥（敏感字段 AES-256） ==========
  encryption:
    # Base64 编码的 256 位密钥。生成方法: openssl rand -base64 32
    key: ${ENCRYPTION_KEY:Q29zdExpbmstQUVTLTI1Ni1LZXktRGV2LUVudmlyb25tZW50LTIwMjY=}

# ========== Redis 共享配置 ==========
spring:
  data:
    redis:
      host: ${REDIS_HOST:host.docker.internal}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      database: 0
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 2
          max-wait: 3000ms

# ========== RabbitMQ 共享配置 ==========
  rabbitmq:
    host: ${RABBITMQ_HOST:host.docker.internal}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:costlink}
    password: ${RABBITMQ_PASSWORD:costlink123}
    virtual-host: /
    # 发送确认
    publisher-confirm-type: correlated
    publisher-returns: true
    # 消费确认
    listener:
      simple:
        acknowledge-mode: manual
        retry:
          enabled: true
          initial-interval: 1000ms
          max-attempts: 3
          multiplier: 2.0

# ========== Sentinel 共享配置 ==========
  cloud:
    sentinel:
      enabled: true
      transport:
        dashboard: ${SENTINEL_DASHBOARD:host.docker.internal:8088}
        port: 8719
      # 熔断后默认降级响应
      datasource:
        ds1:
          nacos:
            server-addr: ${NACOS_SERVER_ADDR:host.docker.internal:8848}
            namespace: ${NACOS_NAMESPACE}
            group-id: DEFAULT_GROUP
            data-id: costlink-sentinel-${spring.profiles.active}.yaml
            data-type: json
            rule-type: flow

# ========== 日志配置 ==========
logging:
  level:
    root: INFO
    com.costlink: DEBUG
    com.alibaba.nacos: WARN
    org.springframework.cloud.gateway: DEBUG
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId:-}] %-5level %logger{36} - %msg%n"

# ========== 文件上传配置 ==========
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 20MB

# ========== Feign 通用配置 ==========
  cloud:
    openfeign:
      client:
        config:
          default:
            connect-timeout: 5000
            read-timeout: 10000
            logger-level: BASIC
      compression:
        request:
          enabled: true
          mime-types: application/json
          min-request-size: 1024
        response:
          enabled: true

# ========== 健康检查 ==========
management:
  endpoint:
    health:
      show-details: always
  health:
    rabbit:
      enabled: true
```

### 5.2 Data ID: `costlink-sentinel-dev.yaml`

Sentinel 流控规则，放在 Nacos 中实现动态更新：

```json
[
  {
    "resource": "POST:/api/reimbursements",
    "limitApp": "default",
    "grade": 1,
    "count": 50,
    "strategy": 0,
    "controlBehavior": 0,
    "clusterMode": false
  },
  {
    "resource": "POST:/api/reimbursements/{id}/submit",
    "limitApp": "default",
    "grade": 1,
    "count": 20,
    "strategy": 0,
    "controlBehavior": 0,
    "clusterMode": false
  },
  {
    "resource": "POST:/api/approvals/{instanceId}/approve",
    "limitApp": "default",
    "grade": 1,
    "count": 30,
    "strategy": 0,
    "controlBehavior": 0,
    "clusterMode": false
  }
]
```

---

## 6. 网关服务配置

### 6.1 Data ID: `costlink-gateway-dev.yaml`

```yaml
# ============================================================
# Data ID: costlink-gateway-dev.yaml
# 服务名:  costlink-gateway
# 端口:    8080
# ============================================================

server:
  port: 8080

spring:
  cloud:
    gateway:
      # ========== 路由规则 ==========
      routes:
        # -- 认证服务（无需鉴权） --
        - id: costlink-auth
          uri: lb://costlink-auth
          predicates:
            - Path=/api/auth/**
          filters:
            - StripPrefix=0

        # -- 报销服务 --
        - id: costlink-reimbursement
          uri: lb://costlink-reimbursement
          predicates:
            - Path=/api/reimbursements/**
          filters:
            - JwtAuth=true
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 100
                redis-rate-limiter.burstCapacity: 200
                redis-rate-limiter.requestedTokens: 1

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

        # -- OCR 服务 --
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

      # ========== 全局 CORS ==========
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

      # ========== 内部接口不对外暴露 ==========
      # /internal/** 路径不在路由表中，外部无法访问

# ========== Gateway 专用限流 ==========
  data:
    redis:
      host: ${REDIS_HOST:host.docker.internal}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}

# ========== 日志 ==========
logging:
  level:
    org.springframework.cloud.gateway: DEBUG
    reactor.netty: INFO
```

### 6.2 JwtAuth 自定义过滤器工厂（代码要点）

```java
// 这个过滤器需要在 gateway 模块中实现
@Component
public class JwtAuthGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JwtAuthGatewayFilterFactory.Config> {

    public JwtAuthGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String authHeader = exchange.getRequest()
                .getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }

            String token = authHeader.substring(7);
            try {
                // 解析 JWT，跨服务不依赖认证服务（避免循环依赖）
                Claims claims = Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

                // 注入用户信息到 Header，下游服务直接用
                ServerHttpRequest request = exchange.getRequest().mutate()
                    .header("X-User-Id", claims.get("userId").toString())
                    .header("X-User-Role", claims.get("role").toString())
                    .header("X-Department-Id", claims.get("departmentId").toString())
                    .build();

                return chain.filter(exchange.mutate().request(request).build());

            } catch (JwtException e) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
        };
    }

    @Data
    public static class Config {
        private boolean enabled;
    }
}
```

---

## 7. 认证服务配置

### 7.1 Data ID: `costlink-auth-dev.yaml`

```yaml
# ============================================================
# Data ID: costlink-auth-dev.yaml
# 服务名:  costlink-auth
# 端口:    8084
# ============================================================

server:
  port: 8084

spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://${DB_HOST:host.docker.internal}:${DB_PORT:3306}/costlink_shared?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4&allowPublicKeyRetrieval=true
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:}
    hikari:
      # 连接池配置
      maximum-pool-size: 10
      minimum-idle: 5
      idle-timeout: 600000
      connection-timeout: 30000
      # 验证连接有效性
      connection-test-query: SELECT 1

mybatis-plus:
  # 实体扫描包
  type-aliases-package: com.costlink.auth.entity
  # 打印 SQL（仅 dev）
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
  global-config:
    db-config:
      # 逻辑删除
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0

# ========== LDAP 集成（dev 环境关闭，用数据库认证） ==========
costlink:
  ldap:
    enabled: false
    urls: ldap://ldap.example.com:389
    base-dn: dc=costlink,dc=com
    user-search-filter: "(uid={0})"

  # ========== 初始管理员账号 ==========
  admin:
    # dev 环境默认管理员（明文密码仅在开发环境使用）
    default-users:
      - username: admin
        password: admin123
        role: ADMIN
        display-name: 系统管理员
```

---

## 8. 报销服务配置

### 8.1 Data ID: `costlink-reimbursement-dev.yaml`

```yaml
# ============================================================
# Data ID: costlink-reimbursement-dev.yaml
# 服务名:  costlink-reimbursement
# 端口:    8081
# ============================================================

server:
  port: 8081

spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://${DB_HOST:host.docker.internal}:${DB_PORT:3306}/costlink_reimbursement?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4&allowPublicKeyRetrieval=true
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10

mybatis-plus:
  type-aliases-package: com.costlink.reimbursement.entity
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0

# ========== 报销业务配置（@RefreshScope 支持热刷新） ==========
costlink:
  reimbursement:
    # 最大费用明细行数
    max-expense-items: 20
    # 单笔报销最大金额（元）
    max-amount: 100000
    # 撤回时限（提交后多少小时内可撤回）
    withdraw-timeout-hours: 2
    # 重复提交时间窗口（秒），防止用户连点
    duplicate-submit-window: 30

# ========== 报销状态机的状态流转规则 ==========
  status-machine:
    transitions:
      DRAFT:                           # 草稿可以操作
        - SUBMIT                       # → 提交
        - UPDATE                       # → 更新
        - DELETE                       # → 删除
      PENDING:                         # 审批中可以操作
        - WITHDRAW                     # → 撤回
      APPROVED:                        # 已通过可以操作
        - MARK_PAID                    # → 标记付款
      PAID:                            # 已付款
        - COMPLETE                     # → 归档

# ========== Saga 事务超时配置 ==========
  saga:
    # 报销提交 Saga 总超时（秒）
    submit-timeout: 30
    # 预算冻结超时（秒）
    budget-freeze-timeout: 5
    # 审批链启动超时（秒）
    approval-start-timeout: 5
```

---

## 9. 预算服务配置

### 9.1 Data ID: `costlink-budget-dev.yaml`

```yaml
# ============================================================
# Data ID: costlink-budget-dev.yaml
# 服务名:  costlink-budget
# 端口:    8082
# ============================================================

server:
  port: 8082

spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://${DB_HOST:host.docker.internal}:${DB_PORT:3306}/costlink_budget?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4&allowPublicKeyRetrieval=true
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10

mybatis-plus:
  type-aliases-package: com.costlink.budget.entity
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0

# ========== 预算业务配置 ==========
costlink:
  budget:
    # 默认控制策略: STRICT(硬控制) / SOFT(软控制) / FLEXIBLE(弹性控制)
    default-control-strategy: STRICT

    # 预算预警阈值（可用余额百分比）
    warning-threshold: 20    # 低于20%触发预警
    critical-threshold: 5    # 低于5%触发严重告警

    # 预算科目是否能互相挪用（弹性控制时生效）
    flexible-transfer-enabled: false

# ========== 分布式锁配置 ==========
  lock:
    # 预算冻结锁超时（秒）
    freeze-lock-timeout: 10
    # 获取锁的等待时间（秒）
    freeze-lock-wait: 3
    # 锁的 Key 前缀
    freeze-lock-key-prefix: "budget:lock:freeze"

# ========== Redisson 分布式锁专用配置 ==========
redisson:
  # 使用共享配置中的 Redis 连接（也可以在此独立配置）
  single-server-config:
    address: "redis://${REDIS_HOST:host.docker.internal}:${REDIS_PORT:6379}"
    password: ${REDIS_PASSWORD:}
    database: 1                # 使用 Redis 1号库
    connection-pool-size: 16
    connection-minimum-idle-size: 8
```

---

## 10. 审批服务配置

### 10.1 Data ID: `costlink-approval-dev.yaml`

```yaml
# ============================================================
# Data ID: costlink-approval-dev.yaml
# 服务名:  costlink-approval
# 端口:    8083
# ============================================================

server:
  port: 8083

spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://${DB_HOST:host.docker.internal}:${DB_PORT:3306}/costlink_approval?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4&allowPublicKeyRetrieval=true
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10

mybatis-plus:
  type-aliases-package: com.costlink.approval.entity
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0

# ========== 审批业务配置 ==========
costlink:
  approval:
    # 默认审批超时（小时），超时自动转审到上一级
    default-timeout-hours: 72

    # 审批操作类型
    actions:
      - APPROVE        # 通过
      - REJECT         # 驳回
      - TRANSFER       # 转审（转给其他人审）
      - ADD_SIGN       # 加签（多加一个审批人）
      - WITHDRAW       # 撤回自己的审批意见

    # 审批链引擎
    chain-engine:
      # 条件评估超时（毫秒）
      evaluation-timeout: 2000
      # 审批节点最大数量
      max-nodes: 10
      # 同一个人既是申请人也是审批人时，自动跳过
      auto-skip-self: true

    # 通知配置
    notification:
      # 审批到达时通知
      on-node-start: true
      # 审批完成时通知
      on-complete: true
      # 审批驳回时通知
      on-reject: true
      # 审批超时前一天提醒
      on-reminder: true
      reminder-before-hours: 24
```

---

## 11. OCR 服务配置

### 11.1 Data ID: `costlink-ocr-dev.yaml`

```yaml
# ============================================================
# Data ID: costlink-ocr-dev.yaml
# 服务名:  costlink-ocr
# 端口:    8085
# 说明:    OCR 服务无数据库依赖，仅连接 Redis 做结果缓存
# ============================================================

server:
  port: 8085

# ========== 百度 OCR 配置 ==========
costlink:
  ocr:
    engine: baidu                    # 当前引擎: baidu / paddle / tesseract
    baidu:
      # API 基础地址
      base-url: https://aip.baidubce.com
      # 百度云应用的 API Key 和 Secret Key
      app-id: ${BAIDU_OCR_APP_ID:}
      api-key: ${BAIDU_OCR_API_KEY:}
      secret-key: ${BAIDU_OCR_SECRET_KEY:}
      # AccessToken 提前刷新时间（秒），默认提前1天
      token-refresh-before-expire: 86400
      # 连接超时（毫秒）
      connect-timeout: 5000
      # 读取超时（毫秒）
      read-timeout: 15000
      # 每日免费额度（百度免费额度为每天500次）
      daily-free-quota: 500

    # 图片预处理
    preprocessor:
      # 图片最大大小（字节），超过压缩
      max-size: 4194304              # 4MB
      # 支持的图片格式
      supported-formats:
        - JPEG
        - PNG
        - BMP
        - PDF                         # 仅第一页
      # 压缩质量（0.0-1.0）
      compression-quality: 0.8

    # 结果缓存
    cache:
      # 相同图片hash的缓存时间（小时）
      result-ttl-hours: 24
      # 缓存键前缀
      key-prefix: "ocr:result:"
      # 按发票号码去重缓存
      invoice-dedup-key-prefix: "ocr:invoice:"

    # 调用限流（自我保护）
    rate-limit:
      # 每秒最大请求数
      max-per-second: 10
      # 队列容量（超出限流时排队等待）
      queue-capacity: 20

    # 引擎切换（预留）
    paddle:
      enabled: false
      server-url: http://localhost:8866/predict/ocr_system
    tesseract:
      enabled: false
      data-path: /usr/share/tesseract-ocr/4.00/tessdata
      language: chi_sim+eng
```

---

## 12. 通知服务配置

### 12.1 Data ID: `costlink-notification-dev.yaml`

```yaml
# ============================================================
# Data ID: costlink-notification-dev.yaml
# 服务名:  costlink-notification
# 端口:    8086
# ============================================================

server:
  port: 8086

spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://${DB_HOST:host.docker.internal}:${DB_PORT:3306}/costlink_notification?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4&allowPublicKeyRetrieval=true
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:}
    hikari:
      maximum-pool-size: 5
      minimum-idle: 2

mybatis-plus:
  type-aliases-package: com.costlink.notification.entity
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl

# ========== 通知渠道配置 ==========
costlink:
  notification:
    # dev 环境只开启站内信，邮件和企微先关闭
    channels:
      in-app:
        enabled: true
      email:
        enabled: false
        host: smtp.example.com
        port: 587
        username: ${EMAIL_USERNAME:}
        password: ${EMAIL_PASSWORD:}
        from: noreply@costlink.com
        properties:
          mail.smtp.auth: true
          mail.smtp.starttls.enable: true
      wechat-work:
        enabled: false
        corp-id: ${WECHAT_CORP_ID:}
        corp-secret: ${WECHAT_CORP_SECRET:}
        agent-id: 1000001
      dingtalk:
        enabled: false
        webhook-url: ${DINGTALK_WEBHOOK:}

    # 消息保留天数
    retention-days: 90
    # 批量发送间隔（秒）
    batch-interval: 30
```

---

## 13. 报表服务配置

### 13.1 Data ID: `costlink-report-dev.yaml`

```yaml
# ============================================================
# Data ID: costlink-report-dev.yaml
# 服务名:  costlink-report
# 端口:    8087
# 说明:    报表服务连接各业务库的只读副本。dev 环境直接连同一数据库
# ============================================================

server:
  port: 8087

# ========== 多数据源配置 ==========
costlink:
  report:
    datasources:
      reimbursement:
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://${DB_HOST:host.docker.internal}:${DB_PORT:3306}/costlink_reimbursement?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4&allowPublicKeyRetrieval=true
        username: ${DB_USERNAME:root}
        password: ${DB_PASSWORD:}
        hikari:
          maximum-pool-size: 3
          minimum-idle: 1
          # 标记为只读连接
          read-only: true

      budget:
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://${DB_HOST:host.docker.internal}:${DB_PORT:3306}/costlink_budget?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4&allowPublicKeyRetrieval=true
        username: ${DB_USERNAME:root}
        password: ${DB_PASSWORD:}
        hikari:
          maximum-pool-size: 3
          minimum-idle: 1
          read-only: true

      approval:
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://${DB_HOST:host.docker.internal}:${DB_PORT:3306}/costlink_approval?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4&allowPublicKeyRetrieval=true
        username: ${DB_USERNAME:root}
        password: ${DB_PASSWORD:}
        hikari:
          maximum-pool-size: 3
          minimum-idle: 1
          read-only: true

# ========== 报表业务配置 ==========
costlink:
  report:
    # 大报表查询超时（秒）
    query-timeout: 30
    # 导出最大行数
    export-max-rows: 50000
    # 报表缓存时间（分钟），避免相同报表重复查询
    cache-ttl-minutes: 5
    # 导出格式
    export-formats:
      - EXCEL
      - PDF
```

---

## 14. Docker Compose 适配

### 14.1 更新后的 docker-compose.yml

原框架文档中的 docker-compose 需要修改两处：移除 Nacos 和 MySQL 容器（它们在 Windows 本机），以及修正服务连接地址。

```yaml
version: '3.8'

services:
  # ========== 注意: Nacos 和 MySQL 运行在 Windows 本机，不在此编排 ==========
  # Nacos: 127.0.0.1:8848
  # MySQL: 127.0.0.1:3306

  redis:
    image: redis:7-alpine
    container_name: costlink-redis
    ports: ["6379:6379"]
    command: redis-server --requirepass ${REDIS_PASSWORD:-}
    volumes: [redis-data:/data]
    networks:
      - costlink-net

  rabbitmq:
    image: rabbitmq:3.12-management-alpine
    container_name: costlink-rabbitmq
    ports: ["5672:5672", "15672:15672"]
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_USERNAME:-costlink}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD:-costlink123}
    networks:
      - costlink-net

  # ========== 微服务 ==========

  costlink-gateway:
    build: ./costlink-gateway
    container_name: costlink-gateway
    ports: ["8080:8080"]
    environment:
      NACOS_SERVER_ADDR: host.docker.internal:8848    # ← Windows 本机的 Nacos
      NACOS_NAMESPACE: ${NACOS_NAMESPACE}
      REDIS_HOST: redis
    depends_on: [redis]
    networks:
      - costlink-net
    extra_hosts:
      - "host.docker.internal:host-gateway"           # ← 确保 Docker 能解析

  costlink-auth:
    build: ./costlink-auth
    container_name: costlink-auth
    ports: ["8084:8084"]
    environment:
      NACOS_SERVER_ADDR: host.docker.internal:8848
      NACOS_NAMESPACE: ${NACOS_NAMESPACE}
      DB_HOST: host.docker.internal                   # ← Windows 本机的 MySQL
      DB_PORT: "3306"
      DB_USERNAME: ${DB_USERNAME:-root}
      DB_PASSWORD: ${DB_PASSWORD}
      REDIS_HOST: redis
    depends_on: [redis]
    networks:
      - costlink-net
    extra_hosts:
      - "host.docker.internal:host-gateway"

  costlink-reimbursement:
    build: ./costlink-reimbursement
    ports: ["8081:8081"]
    environment:
      NACOS_SERVER_ADDR: host.docker.internal:8848
      NACOS_NAMESPACE: ${NACOS_NAMESPACE}
      DB_HOST: host.docker.internal
      DB_PORT: "3306"
      DB_USERNAME: ${DB_USERNAME:-root}
      DB_PASSWORD: ${DB_PASSWORD}
      REDIS_HOST: redis
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_USERNAME: ${RABBITMQ_USERNAME:-costlink}
      RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD:-costlink123}
    depends_on: [redis, rabbitmq]
    networks:
      - costlink-net
    extra_hosts:
      - "host.docker.internal:host-gateway"
    deploy:
      replicas: 2

  costlink-budget:
    build: ./costlink-budget
    ports: ["8082:8082"]
    environment:
      NACOS_SERVER_ADDR: host.docker.internal:8848
      NACOS_NAMESPACE: ${NACOS_NAMESPACE}
      DB_HOST: host.docker.internal
      DB_PORT: "3306"
      DB_USERNAME: ${DB_USERNAME:-root}
      DB_PASSWORD: ${DB_PASSWORD}
      REDIS_HOST: redis
    depends_on: [redis]
    networks:
      - costlink-net
    extra_hosts:
      - "host.docker.internal:host-gateway"

  costlink-approval:
    build: ./costlink-approval
    ports: ["8083:8083"]
    environment:
      NACOS_SERVER_ADDR: host.docker.internal:8848
      NACOS_NAMESPACE: ${NACOS_NAMESPACE}
      DB_HOST: host.docker.internal
      DB_PORT: "3306"
      DB_USERNAME: ${DB_USERNAME:-root}
      DB_PASSWORD: ${DB_PASSWORD}
      REDIS_HOST: redis
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_USERNAME: ${RABBITMQ_USERNAME:-costlink}
      RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD:-costlink123}
    depends_on: [redis, rabbitmq]
    networks:
      - costlink-net
    extra_hosts:
      - "host.docker.internal:host-gateway"
    deploy:
      replicas: 2

  costlink-ocr:
    build: ./costlink-ocr
    ports: ["8085:8085"]
    environment:
      NACOS_SERVER_ADDR: host.docker.internal:8848
      NACOS_NAMESPACE: ${NACOS_NAMESPACE}
      REDIS_HOST: redis
      BAIDU_OCR_APP_ID: ${BAIDU_OCR_APP_ID}
      BAIDU_OCR_API_KEY: ${BAIDU_OCR_API_KEY}
      BAIDU_OCR_SECRET_KEY: ${BAIDU_OCR_SECRET_KEY}
    depends_on: [redis]
    networks:
      - costlink-net
    extra_hosts:
      - "host.docker.internal:host-gateway"
    deploy:
      replicas: 2

  costlink-notification:
    build: ./costlink-notification
    ports: ["8086:8086"]
    environment:
      NACOS_SERVER_ADDR: host.docker.internal:8848
      NACOS_NAMESPACE: ${NACOS_NAMESPACE}
      DB_HOST: host.docker.internal
      DB_PORT: "3306"
      DB_USERNAME: ${DB_USERNAME:-root}
      DB_PASSWORD: ${DB_PASSWORD}
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_USERNAME: ${RABBITMQ_USERNAME:-costlink}
      RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD:-costlink123}
    depends_on: [rabbitmq]
    networks:
      - costlink-net
    extra_hosts:
      - "host.docker.internal:host-gateway"

  costlink-report:
    build: ./costlink-report
    ports: ["8087:8087"]
    environment:
      NACOS_SERVER_ADDR: host.docker.internal:8848
      NACOS_NAMESPACE: ${NACOS_NAMESPACE}
      DB_HOST: host.docker.internal
      DB_PORT: "3306"
      DB_USERNAME: ${DB_USERNAME:-root}
      DB_PASSWORD: ${DB_PASSWORD}
    networks:
      - costlink-net
    extra_hosts:
      - "host.docker.internal:host-gateway"

  # ========== 前端 ==========
  nginx:
    image: nginx:1.24-alpine
    container_name: costlink-nginx
    ports: ["80:80"]
    volumes:
      - ./costlink-frontend/dist:/usr/share/nginx/html
      - ./nginx.conf:/etc/nginx/conf.d/default.conf
    depends_on: [costlink-gateway]
    networks:
      - costlink-net

networks:
  costlink-net:
    driver: bridge

volumes:
  redis-data:
```

### 14.2 .env 文件

```bash
# .env — Docker Compose 环境变量
# 放在 docker-compose.yml 同级目录

# ========== Nacos 配置 ==========
# 填入 Nacos 控制台看到的 Namespace ID
NACOS_NAMESPACE=a1b2c3d4-e5f6-7890-abcd-ef1234567890

# ========== 数据库 ==========
DB_USERNAME=root
DB_PASSWORD=你的MySQL密码

# ========== Redis ==========
REDIS_PASSWORD=

# ========== RabbitMQ ==========
RABBITMQ_USERNAME=costlink
RABBITMQ_PASSWORD=costlink123

# ========== 百度 OCR（dev 环境用免费额度） ==========
BAIDU_OCR_APP_ID=你的AppId
BAIDU_OCR_API_KEY=你的APIKey
BAIDU_OCR_SECRET_KEY=你的SecretKey
```

### 14.3 重要：host.docker.internal 在 WSL2 中的注意事项

Docker Desktop 在 Windows 上默认会将 `host.docker.internal` 解析为 Windows 宿主机的 IP。但在某些 WSL2 配置下，这个解析可能不生效。

**验证方法**：

```bash
# 在 WSL 终端中执行
docker run --rm alpine ping -c 2 host.docker.internal
```

如果能 ping 通，说明没问题。如果 ping 不通，需要在 Docker Desktop 设置中确认：

1. 打开 Docker Desktop → Settings → General
2. 确保 "Use WSL 2 based engine" 已勾选
3. 重启 Docker Desktop

如果仍然无法解析，可以在 docker-compose.yml 的每个服务中添加 `extra_hosts` 并手动指定 Windows 宿主机的 WSL 网关 IP（通常是 WSL 中 `ip route` 显示的默认网关）：

```bash
# 在 WSL 终端中查看网关 IP
ip route | grep default | awk '{print $3}'
# 假设得到 172.25.0.1

# 在 docker-compose.yml 中：
extra_hosts:
  - "host.docker.internal:172.25.0.1"
```

---

## 15. 配置热刷新与运维

### 15.1 哪些配置可以热刷新

标注了 `refresh: true` 的共享配置和标注了 `@RefreshScope` 的业务配置支持不重启服务即时生效：

| 配置类型 | 是否支持热刷新 | 生效方式 |
|---------|--------------|---------|
| 共享配置（shared-configs） | ✅ | Nacos 控制台修改后自动推送 |
| 业务开关（如预算控制策略） | ✅ | 加 `@RefreshScope` |
| 数据库连接 | ❌ | 需要重启 |
| 服务端口 | ❌ | 需要重启 |
| Feign 超时 | ✅ | 加 `@RefreshScope` |
| Sentinel 规则 | ✅ | Sentinel 控制台修改 |

### 15.2 @RefreshScope 使用示例

```java
// 报销服务中需要热刷新的业务配置
@RestController
@RefreshScope   // ← 这个注解让 Bean 在 Nacos 配置变更时自动刷新
@RequestMapping("/api/reimbursements")
public class ReimbursementController {

    @Value("${costlink.reimbursement.max-amount}")
    private BigDecimal maxAmount;   // 修改 Nacos 配置后这个值自动更新

    @Value("${costlink.status-machine.transitions}")
    private Map<String, List<String>> statusTransitions;
}
```

### 15.3 配置变更审计

在 Nacos 控制台中可以看到每个配置的历史版本：

1. 进入「配置管理」→「配置列表」
2. 点击某个 Data ID
3. 点击「历史版本」标签
4. 可以看到谁在什么时间改了什么内容，并支持回滚到任意历史版本

### 15.4 环境间配置同步

从 dev 同步配置到 test 的步骤：

1. Nacos 控制台 → 切换到 `costlink-dev` Namespace
2. 选中要导出的配置 → 点击「导出」
3. 下载 zip 文件
4. 切换到 `costlink-test` Namespace
5. 点击「导入」→ 上传刚才的 zip
6. 逐个修改环境差异项（数据库密码、百度 OCR Key 等）

### 15.5 常见启动问题排查

| 现象 | 可能原因 | 排查步骤 |
|-----|---------|---------|
| 服务连不上 Nacos | 容器内 `host.docker.internal` 解析失败 | `docker run --rm alpine ping host.docker.internal` |
| 配置加载为空 | Namespace ID 写错 | 核对 `bootstrap.yml` 中的 Namespace ID 与 Nacos 控制台一致 |
| 服务注册不上 | 容器注册的是 `127.0.0.1` | 在配置中显式指定 `spring.cloud.nacos.discovery.ip` |
| 配置不刷新 | 缺少 `@RefreshScope` | 检查需要热刷新的 Bean 是否加了注解 |
| Nacos 启动报 MySQL 错误 | MySQL 驱动不兼容 | Nacos 2.3.x 需要 MySQL Connector 8.0+，检查 `nacos/plugins/mysql/` 下的 jar |

---

## 附录: 快速启动检查清单

按顺序执行，每步确认无误后再进入下一步：

- [ ] MySQL 运行中，`127.0.0.1:3306` 可连接
- [ ] `nacos_config` 数据库已创建，`mysql-schema.sql` 已导入
- [ ] Nacos 启动成功，`http://127.0.0.1:8848/nacos` 可访问
- [ ] 三个 Namespace (`costlink-dev`, `costlink-test`, `costlink-prod`) 已创建
- [ ] 所有 Data ID 配置文件已在 dev Namespace 中发布
- [ ] Docker 容器能 ping 通 `host.docker.internal`
- [ ] 依次启动服务，观察 Nacos 控制台「服务管理」→「服务列表」中是否有注册成功
- [ ] 服务启动日志中无 `ConnectException` 或 `NacosException`

---

> **版本记录**: v1.0 — 2026-07-01。覆盖 Nacos 在 Windows + WSL(Docker) 混合环境下的完整配置方案，含所有 8 个微服务的 Nacos 配置文件，三环境 Namespace 规划，以及 Docker Compose 网络适配。
