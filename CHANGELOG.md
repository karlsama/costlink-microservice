# CostLink 变更日志

> 记录已完成的修改和待处理的事项，方便后续回溯。
> 项目: F:\project_007 | 仓库: github.com/karlsama/costlink-microservice

---

## 已完成的架构修改

| 日期 | 变更 | 影响范围 |
|-----|------|---------|
| 07-01 | common 模块 `spring-boot-starter-web` → `spring-web` + `jakarta.servlet-api` | 避免 Gateway(WebFlux) 与 Tomcat 冲突 |
| 07-01 | MqConstants 补充 `EXCHANGE_OCR`、`RK_OCR_COMPLETED`、`RK_OCR_FAILED` | 打通 OCR 识别结果回写链路 |
| 07-01 | docker-compose.yml Nacos 改用 `network_mode: host` | 解决容器无法跨到 Windows 访问 MySQL |
| 07-01 | docker-compose.yml 移除 `version` 字段和 `replicas` | 修复启动报错 |
| 07-01 | init.sql admin 密码改为真实 BCrypt 哈希 | 修复登录失败（假哈希） |
| 07-02 | JwtUtil 密钥处理修正（移除多余 Base64 编码） | 签发和验证 Token 一致性 |
| 07-02 | 所有 JDBC URL 添加 `allowPublicKeyRetrieval=true` | 修复 MySQL 8.0+ 连接报错 |
| 07-02 | 所有 JDBC URL 默认地址改为 `127.0.0.1` | 适配 IDE 开发阶段直连 |
| 07-02 | 新增 JwtUtil 工具类 (`common/util/JwtUtil.java`) | 认证服务和 Gateway 共用 |
| 07-02 | 三份设计文档同步更新 | 与最终代码保持一致 |

---

## 已产出的文件清单

```
F:\project_007\
├── pom.xml                              # 父 POM，9 个子模块
├── docker-compose.yml                   # Nacos(host) + Redis + RabbitMQ + 全量服务
├── .env                                 # 真实环境变量（含百度OCR凭据）
├── .env.example                         # 环境变量模板
├── .gitignore
│
├── costlink-common/pom.xml              # 公共模块（无 Tomcat，无端口冲突）
├── costlink-common/src/main/java/com/costlink/common/
│   ├── dto/Result.java                  # 统一响应
│   ├── dto/PageResult.java              # 分页响应
│   ├── dto/UserContext.java             # 当前用户上下文
│   ├── exception/ErrorCode.java         # 错误码枚举
│   ├── exception/BusinessException.java # 业务异常
│   ├── exception/GlobalExceptionHandler.java # 全局异常拦截
│   ├── feign/BudgetClient.java          # 预算服务 Feign 接口
│   ├── feign/ApprovalClient.java        # 审批服务 Feign 接口
│   ├── feign/AuthClient.java            # 认证服务 Feign 接口（含 UserInfoDTO）
│   ├── feign/OcrClient.java             # OCR 服务 Feign 接口
│   ├── mq/MqConstants.java              # MQ 交换机/队列/路由键（含 OCR 事件）
│   └── util/JwtUtil.java                # JWT 签发与解析
│
├── costlink-auth/pom.xml                # 认证服务骨架
├── costlink-auth/src/main/.../AuthApplication.java
├── costlink-auth/src/main/resources/bootstrap.yml
│
├── (其余 6 个服务模块，同上骨架)
│
├── docs/costlink-auth-dev-guide.md      # 认证服务开发指南（完整独立）
├── init/init.sql                        # 数据库初始化（含修复后的密码哈希）
│
├── costlink-microservice-framework.md   # 架构框架文档
├── costlink-nacos-config-manual.md      # Nacos 配置管理手册
└── costlink-module-grouping-guide.md    # 模块分组与集成规范
```

---

## 当前基础设施状态

| 组件 | 位置 | 访问方式 | 状态 |
|-----|------|---------|------|
| MySQL 8.4 | Windows | `127.0.0.1:3306` | 手动启动 `mysqld` |
| Nacos 2.4.3 | Docker (host网络) | `127.0.0.1:8848` | docker compose up nacos |
| Redis 7 | Docker (bridge网络) | `127.0.0.1:6379` | docker compose up redis |
| RabbitMQ 3.13 | Docker (bridge网络) | `127.0.0.1:5672` / `:15672` | docker compose up rabbitmq |

**MySQL 启动命令**（管理员PowerShell）：
```powershell
cd "C:\Program Files\MySQL\MySQL Server 8.4\bin"
.\mysqld --defaults-file="C:\ProgramData\MySQL\my.ini" --console
```

**中间件启动命令**（WSL终端）：
```bash
cd /mnt/f/project_007
docker compose up nacos redis rabbitmq -d
```

---

## 待处理事项

### 集成阶段必须解决

| 事项 | 说明 | 优先级 |
|-----|------|--------|
| Docker bridge 容器访问 Windows 端口 | 容器内的微服务无法调用 Windows 上的服务（不包含 Nacos，Nacos 已用 host 网络模式解决）。但服务仍需要连 MySQL（bridge 不通）。可能的解法：MySQL 也进 Docker，或全部服务用 host 网络 | **高** |
| 集成方案确定 | 开发阶段 IDE 直连，集成时 docker compose 一键全起。当前 docker-compose.yml 中服务的依赖配置需要根据最终网络方案调整 | **高** |

### 代码层面

| 事项 | 说明 | 优先级 |
|-----|------|--------|
| Nacos 配置录入 | 把手册里的共享配置和各服务独有配置录入 Nacos 控制台（开发前/中做） | **中** |
| 报销服务 ApprovalCompleted 竞态处理 | 审批秒批时事件可能比 Saga 状态更新先到，消费者需加状态校验+重试 | 中 |
| Saga 补偿失败处理 | 预算解冻失败时需要重试+告警，当前只有基础补偿逻辑 | 中 |
| Sentinel 规则录入 | 流控和熔断规则需要实际配到 Nacos | 低 |
| 生产环境 Nacos 集群 | 当前 standalone 模式只适合开发，生产需 3 节点集群 | 低 |

### 文档层面

| 事项 | 说明 | 优先级 |
|-----|------|--------|
| 报销服务开发指南 | 参照 auth 指南格式，单独成文 | 按需要 |
| 预算服务开发指南 | 同上 | 按需要 |
| 审批服务开发指南 | 同上 | 按需要 |

---

## 开发顺序

```
认证服务 → 网关 → 报销服务 → 预算服务 + 审批服务 → OCR + 通知 + 报表
```

当前阶段：**认证服务开发中**

参考文档：[docs/costlink-auth-dev-guide.md](docs/costlink-auth-dev-guide.md)
