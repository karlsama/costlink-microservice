# CostLink 模块分组与集成规范

> **目标**: 将 8 个微服务 + 1 个前端拆分为独立可并行的开发组，通过接口契约和编码规范保证最后无缝集成
> **配套文档**: costlink-microservice-framework.md（架构框架）、costlink-nacos-config-manual.md（Nacos 配置）
> **日期**: 2026-07-01

---

## 目录

1. [核心思路：契约先行](#1-核心思路契约先行)
2. [模块分组与依赖分析](#2-模块分组与依赖分析)
3. [各组开发顺序与交付物](#3-各组开发顺序与交付物)
4. [接口契约标准](#4-接口契约标准)
5. [costlink-common 公共模块规范](#5-costlink-common-公共模块规范)
6. [编码规范](#6-编码规范)
7. [数据库规范](#7-数据库规范)
8. [Git 分支策略](#8-git-分支策略)
9. [集成流程与检查清单](#9-集成流程与检查清单)

---

## 1. 核心思路：契约先行

微服务并行开发最大的坑是什么？是 A 调 B 的接口，A 开发完了，B 还没做好，或者 B 返回的字段名跟 A 预期的不一样，集成时炸了。

解决办法很简单：**先把接口契约定下来，放进公共模块 costlink-common，所有人都能看到。各组对着契约开发，集成时就是顺理成章的事。**

```
第 0 步（所有人一起做 1-2 天）:
  定接口契约 → 写入 costlink-common → 提交到 Git

第 1 步起（各组并行）:
  基设组: 拿着契约搭基础设施
  核心组: 拿着契约写报销/预算/审批
  支持组: 拿着契约写 OCR/通知/报表
  前端组: 拿着契约写页面和 API 调用

最后（集成，1-2 天）:
  docker compose up → 冒烟测试 → 修小问题 → 完成
```

所以整个文档最核心的是第 4 节（接口契约）和第 5 节（公共模块）。这两部分定了，各组才能放心并行。

---

## 2. 模块分组与依赖分析

### 2.1 五组划分

```
┌─────────────────────────────────────────────────────────────┐
│  0. 公共模块（所有人依赖）                                    │
│     costlink-common                                         │
│     ├─ DTO、Result、PageResult、ErrorCode                   │
│     ├─ Feign 接口定义（BudgetClient、ApprovalClient...）     │
│     ├─ RabbitMQ 事件对象（ReimbursementSubmittedEvent...）   │
│     ├─ 工具类（JwtUtil、Encryptor）                         │
│     └─ 全局异常处理（GlobalExceptionHandler）               │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  1. 基础组                   2. 核心业务组                   │
│  ├─ costlink-gateway         ├─ costlink-reimbursement ★   │
│  ├─ costlink-auth             ├─ costlink-budget ★           │
│  └─ 父 POM、Docker Compose   └─ costlink-approval ★         │
│                                                              │
│  3. 支撑服务组                4. 前端组                      │
│  ├─ costlink-ocr              └─ costlink-frontend           │
│  ├─ costlink-notification                                   │
│  └─ costlink-report                                         │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 依赖关系图

```
                    ┌─────────────────┐
                    │  costlink-common │  ← 所有人都依赖它
                    └────────┬────────┘
                             │
          ┌──────────────────┼──────────────────┐
          │                  │                  │
          ▼                  ▼                  ▼
   ┌────────────┐   ┌──────────────┐   ┌──────────────┐
   │  基础组     │   │  核心业务组   │   │  支撑服务组   │
   │            │   │              │   │              │
   │ Gateway ───┼───┼─ 报销服务    │   │  OCR 服务    │
   │            │   │   │  │  │   │   │  通知服务    │
   │ Auth ──────┼───┼─ 预算服务 ◄──┼───┼─ 报表服务    │
   │            │   │   │  │  │   │   │              │
   │ Docker     │   │  审批服务    │   │              │
   │ Compose    │   │              │   │              │
   └──────┬─────┘   └──────┬───────┘   └──────┬───────┘
          │                │                   │
          │                │  Feign 调用       │  MQ 消费者
          │                │  (接口在common)   │  (事件类在common)
          │                │                   │
          └────────────────┼───────────────────┘
                           │
                           ▼
                    ┌──────────────┐
                    │   前端组      │  ← 通过 Gateway 访问所有服务
                    │  Vue 3 SPA   │
                    └──────────────┘
```

### 2.3 各组内部依赖

**基础组** 内部顺序：
```
父 POM → costlink-common 定稿 → costlink-auth → costlink-gateway → Docker Compose
```
Gateway 需要知道 Auth 的路径才能配路由，但只需要路径字符串，不需要 Auth 真的跑起来。

**核心业务组** 内部并行：
```
报销服务 ──Feign──► 预算服务
  │                  ▲
  └────Feign─────────┘
  │
  └────Feign──► 审批服务

看似互相依赖，但实际上：
- 报销服务只需要知道「调用预算服务的 Feign 接口长什么样」
- 这个接口定义在 costlink-common 里
- 所以三个人可以同时开工：
   - 人A: 写报销服务的 Controller + Service（调 Feign 接口，Mock 返回值）
   - 人B: 写预算服务的 Controller（实现 Feign 接口）
   - 人C: 写审批服务的 Controller（实现 Feign 接口）
```

关键点是：开发阶段用 Mock 挡掉对未完成服务的调用，集成时换成真实调用只需要改一行配置。

**支撑服务组** 完全独立：
```
OCR → 接收 MQ 事件或 HTTP 请求 → 调用百度 API → 返回/回写结果
通知 → 消费 MQ 事件 → 渲染模板 → 推送
报表 → 连接只读数据库 → 查询 → 返回 → 导出
```
这三个服务互不依赖，也不被其他服务同步调用（只通过 MQ 异步通信），可以各自独立开发。

---

## 3. 各组开发顺序与交付物

### 3.1 第 0 阶段：契约制定（全组参与，1-2 天）

这是唯一不能并行的事。所有人必须达成一致。

| 步骤 | 产出 | 负责人 |
|-----|------|-------|
| 1. 确认所有服务的 API 路径和请求/响应格式 | API 契约文档（本文第 4 节） | 全员讨论，架构负责人定稿 |
| 2. 写入 costlink-common | common 模块代码（DTO、Feign接口、事件类、ErrorCode） | 基础组 |
| 3. 初始化 Git 仓库和父 POM | 项目骨架可编译通过 | 基础组 |
| 4. 评审通过 | 所有人确认契约无误 | 全员 |

### 3.2 第 1 阶段：并行开发（各组独立，1-2 周）

**基础组交付物**：

```
costlink-microservice/
├── pom.xml                         ← 父 POM，依赖管理
├── costlink-common/                ← 公共模块（第 0 阶段已完成）
├── costlink-gateway/
│   ├── pom.xml
│   └── src/main/java/.../
│       ├── GatewayApplication.java
│       ├── filter/JwtAuthFilter.java
│       └── config/RouteConfig.java
├── costlink-auth/
│   ├── pom.xml
│   └── src/main/java/.../
│       ├── AuthApplication.java
│       ├── controller/AuthController.java
│       ├── service/AuthService.java
│       ├── mapper/UserMapper.java
│       └── entity/User.java
├── docker-compose.yml              ← 能启动 gateway + auth + redis + rabbitmq
└── .env.example
```

**核心业务组交付物**：

```
├── costlink-reimbursement/
│   ├── pom.xml
│   └── src/main/java/.../
│       ├── ReimbursementApplication.java
│       ├── controller/ReimbursementController.java
│       ├── service/ReimbursementService.java
│       ├── service/ReimbursementSubmitSaga.java
│       ├── mapper/ReimbursementMapper.java
│       └── entity/Reimbursement.java
│
├── costlink-budget/
│   ├── pom.xml
│   └── src/main/java/.../（同上结构）
│
└── costlink-approval/
    ├── pom.xml
    └── src/main/java/.../（同上结构）
```

**支撑服务组交付物**：

```
├── costlink-ocr/
│   ├── pom.xml
│   └── src/main/java/.../（同上结构）
│
├── costlink-notification/
│   └── ...
│
└── costlink-report/
    └── ...
```

**前端组交付物**：

```
costlink-frontend/
├── package.json
├── vite.config.ts
└── src/
    ├── api/          ← 对应后端 API 的请求封装
    ├── views/        ← 页面组件
    ├── stores/       ← Pinia 状态管理
    ├── router/       ← 路由配置
    └── components/   ← 公共组件
```

### 3.3 第 2 阶段：集成联调（1-2 天）

1. 各组代码合并到 `develop` 分支
2. `docker compose up` 启动全部服务
3. 检查 Nacos 服务列表是否全部注册成功
4. Gateway 路由是否正常转发
5. 端到端冒烟测试（登录 → 创建报销单 → 提交 → 审批 → 查看预算）
6. 修小问题

---

## 4. 接口契约标准

这部分是整个文档最重要的内容。所有人开发时对着这些接口来，集成时就不会出现"你返的字段我叫另一个名"的问题。

### 4.1 统一响应格式（铁律，不可偏离）

```java
// costlink-common: com.costlink.common.dto.Result<T>
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {
    private int code;
    private String message;
    private T data;
    private long timestamp;

    public static <T> Result<T> ok(T data) {
        return new Result<>(200, "success", data, System.currentTimeMillis());
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null, System.currentTimeMillis());
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode.getCode(), errorCode.getMessage());
    }
}
```

每个 Controller 的返回值必须用 `Result<T>` 包裹，**禁止直接返回裸对象**。

### 4.2 统一错误码

```java
// costlink-common: com.costlink.common.exception.ErrorCode
public enum ErrorCode {

    // 通用错误 10000-10099
    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证或Token已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    DUPLICATE_SUBMIT(10001, "请勿重复提交"),

    // 报销模块 10100-10199
    REIMBURSEMENT_NOT_FOUND(10101, "报销单不存在"),
    REIMBURSEMENT_STATUS_ERROR(10102, "当前状态不允许此操作"),
    REIMBURSEMENT_AMOUNT_EXCEED(10103, "报销金额超过上限"),
    REIMBURSEMENT_WITHDRAW_TIMEOUT(10104, "已超过撤回时限"),

    // 预算模块 10200-10299
    BUDGET_NOT_FOUND(10201, "预算不存在"),
    BUDGET_INSUFFICIENT(10202, "预算余额不足"),
    BUDGET_FREEZE_FAILED(10203, "预算冻结失败"),
    BUDGET_UNFREEZE_FAILED(10204, "预算解冻失败"),

    // 审批模块 10300-10399
    APPROVAL_NOT_FOUND(10301, "审批实例不存在"),
    APPROVAL_ALREADY_PROCESSED(10302, "该节点已被处理"),
    APPROVAL_NOT_AUTHORIZED(10303, "您不是当前审批人"),

    // OCR 模块 10400-10499
    OCR_RECOGNIZE_FAILED(10401, "票据识别失败"),
    OCR_UNSUPPORTED_FORMAT(10402, "不支持的图片格式"),
    OCR_QUOTA_EXCEEDED(10403, "OCR调用额度已用完"),

    // 认证模块 10500-10599
    AUTH_LOGIN_FAILED(10501, "用户名或密码错误"),
    AUTH_TOKEN_EXPIRED(10502, "Token已过期，请重新登录"),
    AUTH_ACCOUNT_DISABLED(10503, "账号已被禁用");
}
```

### 4.3 核心 Feign 接口契约

这些接口定义在 costlink-common 中，核心业务组的三个人必须对着它们开发。

```java
// ============================================================
// 预算服务内部接口（报销服务调用）
// 文件: costlink-common/.../feign/BudgetClient.java
// ============================================================
@FeignClient(name = "costlink-budget", path = "/internal/budgets")
public interface BudgetClient {

    /**
     * 冻结预算金额（报销提交时调用）
     */
    @PostMapping("/freeze")
    Result<BudgetFreezeResponse> freeze(@RequestBody BudgetFreezeRequest request);

    /**
     * 消费冻结金额（审批通过时调用）
     */
    @PostMapping("/consume")
    Result<Void> consume(@RequestBody BudgetConsumeRequest request);

    /**
     * 解冻金额（审批驳回 / 撤回时调用）
     */
    @PostMapping("/unfreeze")
    Result<Void> unfreeze(@RequestBody BudgetUnfreezeRequest request);

    /**
     * 查询可用余额
     */
    @GetMapping("/available")
    Result<BudgetAvailableResponse> getAvailable(
        @RequestParam("departmentId") Long departmentId,
        @RequestParam("category") String category
    );
}

// 请求/响应 DTO（也在 common 中）
@Data
public class BudgetFreezeRequest {
    @NotNull
    private Long reimbursementId;
    @NotEmpty
    private List<BudgetFreezeItem> items;
}

@Data
public class BudgetFreezeItem {
    @NotBlank
    private String category;
    @NotNull
    private BigDecimal amount;
}

@Data
public class BudgetFreezeResponse {
    private Boolean success;
    private BigDecimal availableAfterFreeze;
    private String controlStrategy;  // STRICT / SOFT / FLEXIBLE
    private String message;
}
```

```java
// ============================================================
// 审批服务内部接口（报销服务调用）
// 文件: costlink-common/.../feign/ApprovalClient.java
// ============================================================
@FeignClient(name = "costlink-approval", path = "/internal/approvals")
public interface ApprovalClient {

    /**
     * 启动审批链（报销提交时调用）
     */
    @PostMapping("/start")
    Result<ApprovalStartResponse> start(@RequestBody ApprovalStartRequest request);
}

@Data
public class ApprovalStartRequest {
    @NotNull
    private Long reimbursementId;
    @NotNull
    private Long applicantId;
    @NotNull
    private Long departmentId;
    @NotNull
    private BigDecimal totalAmount;
    @NotBlank
    private String expenseType;
}

@Data
public class ApprovalStartResponse {
    private Long instanceId;           // 审批实例 ID
    private String currentApprover;    // 当前审批人姓名
    private Long currentApproverId;    // 当前审批人 ID
    private List<ApprovalNodeInfo> nodeChain;  // 完整审批链
}
```

```java
// ============================================================
// 认证服务内部接口（审批服务 / 网关调用）
// 文件: costlink-common/.../feign/AuthClient.java
// ============================================================
@FeignClient(name = "costlink-auth", path = "/internal/users")
public interface AuthClient {

    /**
     * 查询用户信息
     */
    @GetMapping("/{userId}")
    Result<UserInfo> getUserById(@PathVariable("userId") Long userId);

    /**
     * 根据角色查询用户列表（审批链路由时使用）
     */
    @GetMapping("/by-role")
    Result<List<UserInfo>> getUsersByRole(
        @RequestParam("role") String role,
        @RequestParam("departmentId") Long departmentId
    );
}

@Data
public class UserInfo {
    private Long id;
    private String username;
    private String displayName;
    private String role;
    private Long departmentId;
    private String email;
}
```

### 4.4 RabbitMQ 事件契约

```java
// ============================================================
// 事件对象（放在 costlink-common 中）
// ============================================================

// 交换机与路由键常量
public class MqConstants {
    public static final String EXCHANGE_REIMBURSEMENT = "costlink.reimbursement";
    public static final String EXCHANGE_APPROVAL = "costlink.approval";
    public static final String EXCHANGE_BUDGET = "costlink.budget";

    // 报销事件路由键
    public static final String RK_REIMBURSEMENT_SUBMITTED = "reimbursement.submitted";
    public static final String RK_REIMBURSEMENT_APPROVED = "reimbursement.approved";
    public static final String RK_REIMBURSEMENT_REJECTED = "reimbursement.rejected";
    public static final String RK_REIMBURSEMENT_PAID = "reimbursement.paid";

    // 审批事件路由键
    public static final String RK_APPROVAL_COMPLETED = "approval.completed";
    public static final String RK_APPROVAL_NODE_COMPLETED = "approval.node.completed";

    // 预算事件路由键
    public static final String RK_BUDGET_EXCEEDED = "budget.exceeded";
    public static final String RK_BUDGET_FROZEN = "budget.frozen";
}

// 报销提交事件
@Data
public class ReimbursementSubmittedEvent implements Serializable {
    private String messageId;
    private String eventType = "REIMBURSEMENT_SUBMITTED";
    private Long timestamp;
    private String source;
    private ReimbursementSubmittedPayload payload;
}

@Data
public class ReimbursementSubmittedPayload implements Serializable {
    private Long reimbursementId;
    private Long applicantId;
    private Long departmentId;
    private BigDecimal totalAmount;
    private String expenseType;
    private String title;
    private List<AttachmentPayload> attachments;  // OCR 服务需要识别
}

@Data
public class AttachmentPayload implements Serializable {
    private Long attachmentId;
    private String fileUrl;
}
```

### 4.5 对外 API 路径规范

| 服务 | 对外路径前缀 | 内部路径前缀（仅 Feign） |
|-----|-------------|----------------------|
| 认证 | `/api/auth/**` | `/internal/users/**` |
| 报销 | `/api/reimbursements/**` | — |
| 预算 | `/api/budgets/**` | `/internal/budgets/**` |
| 审批 | `/api/approvals/**` | `/internal/approvals/**` |
| OCR | `/api/ocr/**` | `/internal/ocr/**` |
| 通知 | `/api/notifications/**` | — |
| 报表 | `/api/reports/**` | — |

**规则**: `/api/**` 路径对外暴露（通过 Gateway），`/internal/**` 路径只能在微服务间通过 Feign 调用，Gateway 不路由 `/internal/**`。

---

## 5. costlink-common 公共模块规范

### 5.1 包结构

```
com.costlink.common
├── dto
│   ├── Result.java              # 统一响应
│   ├── PageResult.java          # 分页响应
│   └── UserContext.java         # 从 Header 解析的用户上下文
├── exception
│   ├── ErrorCode.java           # 错误码枚举
│   ├── BusinessException.java   # 业务异常
│   └── GlobalExceptionHandler.java  # 全局异常拦截
├── feign
│   ├── BudgetClient.java        # 预算服务 Feign 接口
│   ├── ApprovalClient.java      # 审批服务 Feign 接口
│   ├── OcrClient.java           # OCR 服务 Feign 接口
│   └── AuthClient.java          # 认证服务 Feign 接口
├── mq
│   ├── MqConstants.java         # 交换机、路由键常量
│   └── event
│       ├── ReimbursementSubmittedEvent.java
│       ├── ReimbursementApprovedEvent.java
│       ├── ReimbursementRejectedEvent.java
│       ├── ApprovalCompletedEvent.java
│       ├── ApprovalNodeCompletedEvent.java
│       └── BudgetExceededEvent.java
├── util
│   ├── JwtUtil.java             # JWT 解析（各服务和 Gateway 共用）
│   └── FinancialDataEncryptor.java  # AES 加解密
└── config
    └── FeignConfig.java         # Feign 拦截器（自动传递 JWT Token）
```

### 5.2 Feign 拦截器（Token 透传）

Feign 调用时，需要把当前请求的 JWT Token 透传给下游服务。这个拦截器在 common 中一次配置，所有 Feign 客户端自动生效：

```java
// costlink-common: com.costlink.common.config.FeignConfig
@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor requestInterceptor() {
        return requestTemplate -> {
            // 从当前请求上下文获取 Token（Gateway 已注入到 Header）
            ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                String token = request.getHeader(HttpHeaders.AUTHORIZATION);
                if (token != null) {
                    requestTemplate.header(HttpHeaders.AUTHORIZATION, token);
                }
            }
        };
    }
}
```

### 5.3 UserContext 工具类

每个服务需要知道"当前是谁在操作"，从 Gateway 注入的 Header 中提取：

```java
// costlink-common: com.costlink.common.dto.UserContext
public class UserContext {

    private static final ThreadLocal<UserInfo> CONTEXT = new ThreadLocal<>();

    public static void set(UserInfo user) {
        CONTEXT.set(user);
    }

    public static UserInfo get() {
        return CONTEXT.get();
    }

    public static Long getUserId() {
        UserInfo user = CONTEXT.get();
        return user != null ? user.getUserId() : null;
    }

    public static String getRole() {
        UserInfo user = CONTEXT.get();
        return user != null ? user.getRole() : null;
    }

    public static Long getDepartmentId() {
        UserInfo user = CONTEXT.get();
        return user != null ? user.getDepartmentId() : null;
    }

    public static void clear() {
        CONTEXT.remove();
    }
}

// 配合 Filter 使用（每个服务都需要配置一个）
@Component
public class UserContextFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        UserInfo user = new UserInfo();
        user.setUserId(parseLong(httpRequest.getHeader("X-User-Id")));
        user.setRole(httpRequest.getHeader("X-User-Role"));
        user.setDepartmentId(parseLong(httpRequest.getHeader("X-Department-Id")));

        UserContext.set(user);
        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}
```

### 5.4 GlobalExceptionHandler

统一异常处理，放在 common 中，所有服务自动继承：

```java
// costlink-common: com.costlink.common.exception.GlobalExceptionHandler
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return Result.fail(ErrorCode.BAD_REQUEST.getCode(), msg);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnknown(Exception e) {
        log.error("未预期异常", e);
        return Result.fail(ErrorCode.INTERNAL_ERROR);
    }
}
```

---

## 6. 编码规范

### 6.1 服务模块命名

| 层级 | 命名规则 | 示例 |
|-----|---------|------|
| Maven module | `costlink-{服务名}` | `costlink-reimbursement` |
| Spring application name | `costlink-{服务名}` | `costlink-reimbursement` |
| Java package | `com.costlink.{服务名}` | `com.costlink.reimbursement` |
| Nacos service ID | `costlink-{服务名}` | `costlink-reimbursement` |
| Docker image | `costlink/{服务名}:{version}` | `costlink/reimbursement:1.0` |
| Docker container | `costlink-{服务名}` | `costlink-reimbursement` |

### 6.2 Java 包内结构（每个服务统一）

```
com.costlink.{服务名}
├── {Service}Application.java      # 启动类
├── controller/
│   ├── XxxController.java         # 对外 REST API
│   └── XxxInternalController.java # 内部 Feign 接口实现
├── service/
│   ├── XxxService.java            # 接口
│   └── impl/
│       └── XxxServiceImpl.java    # 实现
├── mapper/
│   └── XxxMapper.java             # MyBatis-Plus Mapper
├── entity/
│   └── Xxx.java                   # 数据库实体
├── dto/                            # 本服务专用的 DTO（公共的放 common）
│   ├── XxxCreateRequest.java
│   └── XxxQueryRequest.java
├── mq/                             # 本服务的消息生产者和消费者
│   ├── XxxEventPublisher.java
│   └── XxxEventConsumer.java
└── config/
    ├── SentinelConfig.java
    └── XxxConfig.java
```

### 6.3 Controller 写法规范

```java
@RestController
@RequestMapping("/api/reimbursements")
@RequiredArgsConstructor
@Validated
public class ReimbursementController {

    private final ReimbursementService reimbursementService;

    /**
     * 分页查询报销列表
     */
    @GetMapping
    @PreAuthorize("hasAuthority('REIMBURSEMENT:VIEW')")
    public Result<PageResult<ReimbursementListVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {

        PageResult<ReimbursementListVO> result =
            reimbursementService.listByPage(page, size, status);
        return Result.ok(result);
    }

    /**
     * 创建报销单
     */
    @PostMapping
    @PreAuthorize("hasAuthority('REIMBURSEMENT:CREATE')")
    public Result<ReimbursementCreateVO> create(
            @Valid @RequestBody ReimbursementCreateRequest request) {

        ReimbursementCreateVO vo = reimbursementService.create(request);
        return Result.ok(vo);
    }
}
```

**规范要点**：
1. 用 `@RequiredArgsConstructor` 而不是 `@Autowired`
2. 入参加 `@Valid` 做参数校验
3. 权限用 `@PreAuthorize`
4. 返回值必须 `Result<T>` 包裹
5. Controller 不写业务逻辑，只做参数接收和结果返回

### 6.4 日志规范

```java
@Slf4j  // Lombok
@Service
public class ReimbursementServiceImpl implements ReimbursementService {

    public void submit(Long id) {
        // INFO: 关键业务节点
        log.info("开始提交报销单, reimbursementId={}, userId={}", id, UserContext.getUserId());

        // DEBUG: 中间过程
        log.debug("预算冻结结果: {}", freezeResponse);

        // WARN: 异常但可恢复
        log.warn("OCR识别超时，降级为手动输入, attachmentId={}", attachmentId);

        // ERROR: 需要人工介入
        log.error("报销单提交Saga失败, reimbursementId={}, step={}", id, failedStep, exception);
    }
}
```

日志中必须包含关键业务 ID（报销单号、用户 ID 等），方便 ELK 检索。**禁止** `log.info("提交成功")` 这种没有 ID 的日志。

### 6.5 数据库实体规范

```java
@Data
@TableName("reimbursement")
public class Reimbursement {

    @TableId(type = IdType.ASSIGN_ID)  // 雪花算法，分布式唯一
    private Long id;

    private Long applicantId;
    private Long departmentId;
    private String title;
    private BigDecimal totalAmount;
    private String expenseType;
    private String status;

    @TableLogic  // MyBatis-Plus 逻辑删除
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
```

---

## 7. 数据库规范

### 7.1 数据库命名

| 规则 | 示例 |
|-----|------|
| 数据库名: `costlink_{服务名}` | `costlink_reimbursement` |
| 表名: 小写下划线，单数 | `reimbursement`, `expense_item` |
| 主键: `id`，BIGINT，雪花算法 | — |
| 外键: `{关联表名}_id` | `reimbursement_id`, `applicant_id` |
| 时间字段: `create_time`, `update_time` | 统一命名 |
| 逻辑删除: `deleted` TINYINT(1) | 0=正常, 1=删除 |
| 金额字段: `DECIMAL(14,2)` | 支持百万级精度 |

### 7.2 每个服务必须有

```sql
-- 每个数据库的每张表都必须包含
create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
update_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
deleted      TINYINT  NOT NULL DEFAULT 0,

INDEX idx_create_time (create_time),
INDEX idx_update_time (update_time)
```

---

## 8. Git 分支策略

### 8.1 分支模型（简化版 Git Flow）

```
main
  │
  ├── develop                    ← 集成开发分支（各组代码合并到这里）
  │     │
  │     ├── group-foundation     ← 基础组开发分支
  │     │     ├── feature/gateway-jwt-filter
  │     │     └── feature/auth-login
  │     │
  │     ├── group-core           ← 核心业务组开发分支
  │     │     ├── feature/reimbursement-crud
  │     │     ├── feature/budget-freeze
  │     │     └── feature/approval-chain
  │     │
  │     ├── group-support        ← 支撑服务组开发分支
  │     │     ├── feature/ocr-baidu
  │     │     ├── feature/notification
  │     │     └── feature/report
  │     │
  │     └── group-frontend       ← 前端组开发分支
  │           ├── feature/reimbursement-page
  │           └── feature/budget-dashboard
  │
  └── release/v1.0               ← 发布分支
```

### 8.2 操作流程

```bash
# 各组初始化
git clone <仓库地址>
git checkout -b develop origin/develop

# 各组创建自己的分支
git checkout -b group-foundation develop   # 基础组
git checkout -b group-core develop         # 核心组
git checkout -b group-support develop      # 支撑组
git checkout -b group-frontend develop     # 前端组

# 组内开发 feature
git checkout -b feature/reimbursement-crud group-core
# ... 开发并提交 ...
git push origin feature/reimbursement-crud

# feature 完成后合并到组分支
git checkout group-core
git merge feature/reimbursement-crud

# 集成日 — 各组分支合并到 develop
git checkout develop
git merge group-foundation
git merge group-core
git merge group-support
git merge group-frontend
# 解决冲突 → 冒烟测试 → 完成

# 发布
git checkout -b release/v1.0 develop
# 修 bug → 打 tag
git tag v1.0.0
git checkout main
git merge release/v1.0
```

### 8.3 Commit 规范

```
格式: <type>(<scope>): <subject>

type:
  feat     — 新功能
  fix      — Bug 修复
  refactor — 重构
  docs     — 文档
  test     — 测试
  chore    — 构建/工具

示例:
  feat(reimbursement): 实现报销单创建和提交接口
  fix(budget): 修复高并发下预算超扣问题
  docs(common): 更新 Feign 接口契约注释
```

---

## 9. 集成流程与检查清单

### 9.1 集成前置条件

各组合并到 develop 之前，各自确认以下检查项全部通过：

**基础组**：
- [ ] `mvn clean install -DskipTests` 在根目录下编译全部模块通过
- [ ] `docker compose up gateway auth redis rabbitmq` 四个服务正常启动
- [ ] Nacos 控制台能看到 `costlink-gateway` 和 `costlink-auth` 服务
- [ ] `POST /api/auth/login` 返回合法 JWT Token
- [ ] 带 Token 访问 Gateway 路由的接口正常转发

**核心业务组**：
- [ ] 报销单 CRUD 全部接口正常（先用 Mock 的 Feign 客户端验证）
- [ ] 预算服务的 Feign 接口实现完整，单元测试覆盖 `freeze/consume/unfreeze`
- [ ] 审批链引擎能根据金额 + 费用类型正确生成审批链
- [ ] RabbitMQ 消息能正常发送（检查 RabbitMQ 管理界面有消息进入队列）

**支撑服务组**：
- [ ] OCR 服务调用百度 API 返回正确识别结果
- [ ] 通知服务消费 MQ 事件并写入数据库
- [ ] 报表服务能连接三个只读数据源并执行查询

**前端组**：
- [ ] `npm run build` 无报错
- [ ] 各页面路由配置完整
- [ ] API 请求模块路径与后端 Controller 路径一致

### 9.2 集成日操作步骤

**第 1 步：基础设施就绪**

```bash
# Windows 端确认
# 1. MySQL 运行中
# 2. Nacos 运行中，127.0.0.1:8848/nacos 可访问
# 3. 各数据库已创建
```

**第 2 步：代码合并**

```bash
git checkout develop
git merge group-foundation
git merge group-core
git merge group-support
git merge group-frontend
# 手动解决冲突（主要是 pom.xml 和 common 模块）
mvn clean install -DskipTests  # 确保编译通过
```

**第 3 步：启动全部服务**

```bash
docker compose up -d
# 等待所有服务启动完成（约 1-2 分钟）
docker compose ps  # 确认全部 running
```

**第 4 步：验证服务注册**

打开 Nacos 控制台 `http://127.0.0.1:8848/nacos` → 服务管理 → 服务列表，确认以下 8 个服务全部在线：

- costlink-gateway
- costlink-auth
- costlink-reimbursement
- costlink-budget
- costlink-approval
- costlink-ocr
- costlink-notification
- costlink-report

**第 5 步：端到端冒烟测试**

按以下顺序手动测试关键链路，每一步失败则停止排查：

```bash
# 1. 登录
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
# 预期: 返回 Token 和用户信息

# 2. 查询预算（用上一步的 Token 替换 {TOKEN}）
curl http://localhost:8080/api/budgets/available?departmentId=10&category=TRAVEL \
  -H "Authorization: Bearer {TOKEN}"
# 预期: 返回可用余额

# 3. 创建报销单（草稿）
curl -X POST http://localhost:8080/api/reimbursements \
  -H "Authorization: Bearer {TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"title":"测试报销","expenseType":"TRAVEL","items":[{"category":"TRAVEL_TRANSPORT","amount":100.00}]}'
# 预期: 返回报销单 ID

# 4. 提交报销单（触发预算冻结 + 审批链启动）
curl -X POST http://localhost:8080/api/reimbursements/{id}/submit \
  -H "Authorization: Bearer {TOKEN}"
# 预期: 状态变为 PENDING，Budget 冻结金额增加

# 5. 审批通过
curl -X POST http://localhost:8080/api/approvals/{instanceId}/approve \
  -H "Authorization: Bearer {TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"action":"APPROVE","comment":"同意"}'
# 预期: 审批状态更新，预算消费
```

**第 6 步：问题收尾**

冒烟测试中出现的问题记录到 issue，小问题当场修，大问题回各自组分支修。所有 issue 修完后打 tag。

### 9.3 常见集成问题速查

| 现象 | 可能原因 | 解决 |
|-----|---------|------|
| 服务注册不到 Nacos | 容器内 `host.docker.internal` 不通 | `docker run --rm alpine ping host.docker.internal` |
| Feign 调用 404 | 内部接口路径不一致 | 检查 `@RequestMapping` 路径是否与 common 中 Feign 接口一致 |
| MQ 消息收不到 | 交换机/队列/路由键不匹配 | 确认各服务使用的常量来自 common 的 `MqConstants` |
| 数据库连不上 | MySQL 不允许远程连接 | 检查 MySQL 用户是否 `'root'@'%'` 而非 `'root'@'localhost'` |
| 前端 404 | Nginx 配置或 API 路径错误 | 检查 nginx.conf 的 proxy_pass 指向 Gateway |
| 金额精度丢失 | 用了 Double 而非 BigDecimal | 全局搜索 Double，全部替换为 BigDecimal |

---

## 附录: 各组开发 Mock 指南

核心业务组开发报销服务时，预算和审批可能还没好。这时可以用 Mock 挡掉：

```java
// 报销服务中，开发阶段用 @Profile("mock") 激活 Mock Bean
@Configuration
@Profile("mock")
public class MockFeignConfig {

    @Bean
    @Primary
    public BudgetClient mockBudgetClient() {
        return new BudgetClient() {
            @Override
            public Result<BudgetFreezeResponse> freeze(BudgetFreezeRequest request) {
                BudgetFreezeResponse resp = new BudgetFreezeResponse();
                resp.setSuccess(true);
                resp.setAvailableAfterFreeze(BigDecimal.valueOf(50000));
                resp.setControlStrategy("STRICT");
                resp.setMessage("Mock: 预算冻结成功");
                return Result.ok(resp);
            }
            // ... 其他方法类似
        };
    }
}
```

启动时指定 profile 使用 Mock：

```bash
# docker-compose.yml 中
costlink-reimbursement:
  environment:
    SPRING_PROFILES_ACTIVE: dev,mock
```

集成时去掉 `mock` profile 即可切换到真实 Feign 调用。

---

> **版本记录**: v1.0 — 2026-07-01。定义了 CostLink 微服务项目的五组分工方案、接口契约标准、编码和数据库规范、Git 分支策略以及完整的集成流程检查清单。
