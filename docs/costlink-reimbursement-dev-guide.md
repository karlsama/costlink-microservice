# costlink-reimbursement 开发指南

> 面向实际开发，一份文档写完报销服务。不需要回头看其他文件。
> 2026-07-03

---

## 1. 你要做一个什么

**一句话**：报销服务的使命是管理报销单的完整生命周期——从创建、提交（触发审批链+预算冻结）、到审批结果处理、付款标记——同时作为微服务体系的 Saga 编排者，协调预算和审批两个下游服务。

**你的上游**：前端（通过 Gateway）调 `POST /api/reimbursements`、`POST /api/reimbursements/{id}/submit` 等。

**你的下游**：预算服务（Feign 调用冻结/消费/解冻）、审批服务（Feign 调用启动审批链）、OCR 服务（Feign + MQ 事件）。

**你消费的 MQ 事件**：审批完成事件（更新报销单状态）、OCR 识别完成事件（回写票据金额）。

## 2. 你要提前准备好的东西

**外部依赖（都已就位）**：

| 输入 | 来源 | 状态 |
|-----|------|------|
| MySQL `costlink_reimbursement` | `127.0.0.1:3306`，init.sql 建好 5 张表 | ✅ |
| Nacos 地址 | bootstrap.yml（`127.0.0.1:8848`） | ✅ |
| Redis | 共享配置 `127.0.0.1:6379` | ✅ |
| RabbitMQ | 共享配置 `127.0.0.1:5672` | ✅ |
| 百度 OCR 凭据 | 不直接调——通过 OcrClient（Feign）或 MQ 事件 | — |

**你不需要的东西**：

| 不需要 | 原因 |
|-------|------|
| 预算服务的代码 | 通过 Feign 接口调用，不需要知道它内部实现 |
| 审批服务的代码 | 同上 |
| 百度 API Key | OCR 服务管理 AccessToken，报销服务不碰 |

---

## 3. 数据库——5 张表

全部在 `costlink_reimbursement` 库中，init.sql 已建好：

```
reimbursement            — 报销单主表（id, applicant_id, title, total_amount, status, approval_instance_id...）
expense_item             — 费用明细行（reimbursement_id, category, amount, receipt_date）
reimbursement_attachment — 票据附件（file_name, file_url, ocr_amount, ocr_status）
payment_record           — 付款记录（payee_id, amount, pay_method, pay_status）
outbox_message           — 本地消息表（保证 DB 写入 + MQ 发送的原子性）
```

**5 张表你都需要写对应的 Entity + Mapper**。特别注意 `outbox_message`：它不是业务表，但它是可靠性机制的核心——报销提交时必须同时写报销单和 outbox 记录，定时任务扫 outbox 补发失败的 MQ 消息。

---

## 4. 你要写的接口——对外 + 对内

### 4.1 对外接口（走 Gateway，前端调）

| 方法 | 路径 | 说明 |
|-----|------|------|
| POST | `/api/reimbursements` | 创建报销单（草稿） |
| PUT  | `/api/reimbursements/{id}` | 更新报销单 |
| POST | `/api/reimbursements/{id}/attachments` | 上传票据图片 → 触发OCR识别（新增） |
| GET  | `/api/reimbursements/{id}` | 查询报销单详情 |
| GET  | `/api/reimbursements?page=&status=` | 分页查询报销列表 |
| POST | `/api/reimbursements/{id}/submit` | 提交报销单（核心，触发 Saga） |
| POST | `/api/reimbursements/{id}/withdraw` | 撤回报销单 |
| POST | `/api/reimbursements/{id}/mark-paid` | 标记已付款（财务操作） |
| DELETE | `/api/reimbursements/{id}` | 删除草稿（逻辑删除） |

### 4.2 内部 MQ 消费者（没有 Feign 被调接口）

报销服务**不提供内部 Feign 接口给其他服务调**。其他服务通过 MQ 事件通知报销服务。

**消费的事件**：

| 事件（来自） | 队列 | 处理逻辑 |
|------------|------|---------|
| `approval.completed`（审批服务） | `q.approval.completed` | 审批全通过→状态改 APPROVED；驳回→状态改 REJECTED |
| `ocr.completed`（OCR 服务） | `q.ocr.completed` | 把识别结果写入 `reimbursement_attachment.ocr_amount` 和 `ocr_result` |
| `ocr.failed`（OCR 服务） | `q.ocr.failed` | 标记 `ocr_status = FAILED` |

### 4.3 票据上传接口（新增，连接 OCR）

**POST /api/reimbursements/{id}/attachments**

前端上传发票图片后调用此接口。报销服务接收图片、存附件记录、触发 OCR 异步识别。

```java
// 请求（前端发）
{
  "fileName": "invoice_001.jpg",
  "base64Image": "/9j/4AAQSkZJRg..."   // 图片 Base64 编码
}

// 返回
{
  "code": 200,
  "data": {
    "attachmentId": 301,
    "ocrStatus": "PROCESSING"           // 立即返回，OCR 异步后台处理
  }
}
```

**实现逻辑**（ReimbursementServiceImpl 中）：

```java
public Result<AttachmentVO> uploadAttachment(Long reimbursementId, AttachmentUploadRequest req) {
    // 1. 校验报销单存在且状态=DRAFT
    Reimbursement r = reimbursementMapper.selectById(reimbursementId);
    if (r == null || !"DRAFT".equals(r.getStatus())) {
        throw new BusinessException(ErrorCode.REIMBURSEMENT_STATUS_ERROR);
    }

    // 2. 计算文件 MD5（OCR 去重用）
    byte[] imageBytes = Base64.getDecoder().decode(req.getBase64Image());
    String fileHash = DigestUtils.md5Hex(imageBytes);

    // 3. 存附件记录（ocr_status = PROCESSING）
    Attachment att = new Attachment();
    att.setReimbursementId(reimbursementId);
    att.setFileName(req.getFileName());
    att.setFileHash(fileHash);
    att.setFileSize((long) imageBytes.length);
    att.setOcrStatus("PROCESSING");
    attachmentMapper.insert(att);

    // 4. 调 OCR 异步识别（带 Base64 图片数据）
    OcrClient.RecognizeRequest ocrReq = new OcrClient.RecognizeRequest();
    ocrReq.setAttachmentId(att.getId());
    ocrReq.setReimbursementId(reimbursementId);
    ocrReq.setBase64Image(req.getBase64Image());
    ocrReq.setFileHash(fileHash);
    ocrClient.recognizeAsync(ocrReq);

    // 5. 返回
    AttachmentVO vo = new AttachmentVO();
    vo.setAttachmentId(att.getId());
    vo.setOcrStatus("PROCESSING");
    return Result.ok(vo);
}
```

**前端如何用**：用户填报销单时点"上传发票"，前端用 `FileReader.readAsDataURL()` 把图片文件转 Base64，调此接口。返回后前端立刻显示"识别中……"，等 OCR 完成后刷新报销单详情就能看到识别出的金额。

---

## 5. 你要调的下游——Feign 接口

这些在 `costlink-common` 中已定义好，直接注入使用，不需要新建：

| 接口 | 类 | 调用时机 |
|-----|---|---------|
| BudgetClient | `common/feign/BudgetClient.java` | 提交时调 `freeze()`，撤回时调 `unfreeze()` |
| ApprovalClient | `common/feign/ApprovalClient.java` | 提交时调 `start()` |
| OcrClient | `common/feign/OcrClient.java` | 上传票据后调 `recognizeAsync()` |

**关键 Feign 接口签名**（对着写调用代码）：

```java
// BudgetClient — 三个方法
Result<FreezeResponse> freeze(FreezeRequest request);    // 请求含 reimbursementId + departmentId + items
Result<Void> consume(ConsumeRequest request);            // 审批通过后消费金额
Result<Void> unfreeze(UnfreezeRequest request);          // 审批驳回/撤回时解冻

// ApprovalClient — 一个方法
Result<StartResponse> start(StartRequest request);       // 请求含 reimbursementId + applicantId + amount

// OcrClient — 两个方法
Result<Void> recognizeAsync(RecognizeRequest request);   // 异步识别
Result<OcrResultDTO> recognize(RecognizeRequest request);// 同步识别（预览用）
```

---

## 6. 你要发布的 MQ 事件

| 事件 | 路由键 | 触发时机 | 消费者 |
|-----|-------|---------|-------|
| 报销单已提交 | `reimbursement.submitted` | Saga 完成（状态=PENDING）后 | 预算服务(冻结)、OCR服务(识别) |

---

## 7. 核心流程——提单 Saga

这是整个报销服务里最复杂也最重要的部分。每一步都不可跳过：

```
POST /api/reimbursements/{id}/submit 被调用
  │
  ├─ 1. 校验：状态必须=DRAFT，金额>0，费用明细非空
  │
  ├─ 2. 本地事务（DB 原子操作）：
  │     创建 outbox_message（消息体先写好，状态=PENDING）
  │
  ├─ 3. Feign 调用 BudgetClient.freeze()
  │     请求含 reimbursementId + departmentId（从 UserContext 取） + items
  │     ├─ 成功 → 继续
  │     └─ 失败 → 返回"预算不足，可用: xxx" → 流程终止
  │
  ├─ 4. Feign 调用 ApprovalClient.start()
  │     ├─ 成功 → 继续
  │     └─ 失败 → 补偿：BudgetClient.unfreeze() → 返回错误
  │
  ├─ 5. 本地事务（DB 原子操作）：
  │     更新 reimbursement.status = PENDING
  │     更新 reimbursement.approval_instance_id
  │     更新 outbox_message.status = READY（标记可发送）
  │
  └─ 6. 定时任务扫 outbox（status=READY）→ 发送到 RabbitMQ → 改 status=SENT
```

**补偿机制**：如果第 4 步失败，必须调 `BudgetClient.unfreeze()` 把第 3 步冻结的金额退回去。这是 Saga 的唯一补偿动作。

**竞态处理**（之前审查时发现的问题）：如果审批链只有一个节点且审批人秒批，`ApprovalCompleted` 事件可能在步骤 5 还没把状态改成 PENDING 时就飞到报销服务。消费者代码里必须加状态校验——状态不是 PENDING 就 `nack` 回队列重试，最多重试 3 次。

---

## 8. 你要写的代码文件

### 8.1 目录结构

```
costlink-reimbursement/src/main/java/com/costlink/reimbursement/
├── ReimbursementApplication.java              ← 启动类（加 @MapperScan）
├── controller/
│   └── ReimbursementController.java           ← 对外 8 个 REST 接口
├── service/
│   ├── ReimbursementService.java              ← 接口
│   └── impl/
│       └── ReimbursementServiceImpl.java      ← CRUD + 提交 Saga 编排
├── mapper/
│   ├── ReimbursementMapper.java
│   ├── ExpenseItemMapper.java
│   ├── AttachmentMapper.java
│   ├── PaymentRecordMapper.java
│   └── OutboxMessageMapper.java
├── entity/
│   ├── Reimbursement.java
│   ├── ExpenseItem.java
│   ├── Attachment.java
│   ├── PaymentRecord.java
│   └── OutboxMessage.java
├── dto/
│   ├── event/
│   │   └── (事件类已移至 common/mq/event/，此处不再定义)
│   ├── request/
│   │   ├── ReimbursementCreateRequest.java    ← 创建报销请求 DTO
│   │   └── AttachmentUploadRequest.java       ← 票据上传请求 DTO（新增）
│   └── response/
│       ├── ReimbursementVO.java               ← 返回给前端的 VO
│       └── AttachmentVO.java                  ← 附件返回 VO（新增）
├── mq/
│   ├── ReimbursementEventPublisher.java       ← 发 MQ 事件（调 RabbitTemplate）
│   └── ReimbursementEventConsumer.java        ← 消费 ApprovalCompleted + OCR 事件
├── saga/
│   └── ReimbursementSubmitSaga.java           ← 提交流程编排（6 步）
├── config/
│   ├── ReimbursementConfig.java               ← JwtUtil Bean + 通用配置
│   ├── FeignClientConfig.java                  ← @EnableFeignClients（@Profile("!mock")）
│   └── MockFeignConfig.java                    ← Mock 模式：假 Feign Bean（@Profile("mock")）
└── machine/
    └── ReimbursementStatusMachine.java        ← 状态机（合法状态转换校验）
```

### 8.2 启动类与 Feign 配置（修改）

**ReimbursementApplication.java**：

```java
@SpringBootApplication(scanBasePackages = {"com.costlink.reimbursement", "com.costlink.common"})
@EnableDiscoveryClient
@MapperScan("com.costlink.reimbursement.mapper")
// 注意: @EnableFeignClients 不在此处，见下方独立配置类
public class ReimbursementApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReimbursementApplication.class, args);
    }
}
```

**新建 `config/FeignClientConfig.java`**：

```java
@Configuration
@Profile("!mock")   // ← mock 模式下不创建 Feign 客户端，避免与 Mock Bean 冲突
@EnableFeignClients(basePackages = "com.costlink.common.feign")
public class FeignClientConfig {
}
```

把 `@EnableFeignClients` 从启动类上拆出来单独放，原因：当 `mock` profile 激活时，Feign 客户端不应该创建（Mock Bean 会接管 Spring 容器中的接口实现）。Profile 注解不能加在 `@SpringBootApplication` 上（那会停掉整个服务），只能加在独立的配置类上。

**关于 MetaObjectHandler**：`costlink-common` 已提供 `BaseMetaObjectHandler`（`@ConditionalOnClass`），所有 MyBatis-Plus 服务自动继承 `createTime` 和 `updateTime` 自动填充。**本服务不需要自己写。**

### 8.3 ReimbursementController 要点

- 所有返回值必须 `Result<T>` 包裹
- 校验用 `@Valid` + 请求 DTO
- 权限用 `@PreAuthorize`（REIMBURSEMENT:CREATE、REIMBURSEMENT:APPROVE 等）
- Controller 不写业务逻辑——只接收参数、调 Service、返回结果

### 8.4 ReimbursementSubmitSaga（核心编排）

按照上面第 7 节的 6 步流程写。注意：

1. **用 `@Transactional` 保护本地数据库操作**，但不跨服务
2. **Feign 调用不在 `@Transactional` 内部**——在事务外调远程服务，失败后补偿
3. **outbox 消息必须跟 reimbursement 在同一事务里创建**
4. **定时任务**用 `@Scheduled(fixedDelay = 5000)` 扫 `outbox_message WHERE status = 'READY'`

### 8.5 MQ 消费者

**审批完成事件处理**：

```java
@RabbitListener(queues = MqConstants.QUEUE_APPROVAL_COMPLETED)
public void onApprovalCompleted(ApprovalCompletedEvent event) {
    // ⚠️ 检查报销单状态：不是 PENDING → nack 重试（防止 Saga 没改完状态前事件先到）
    Reimbursement r = reimbursementMapper.selectById(event.getReimbursementId());
    if (!"PENDING".equals(r.getStatus())) {
        throw new AmqpRejectAndDontRequeueException("状态不是PENDING");
    }

    if ("APPROVED".equals(event.getAction())) {
        r.setStatus("APPROVED");
        r.setApproveTime(LocalDateTime.now());
        reimbursementMapper.updateById(r);
        eventPublisher.publishReimbursementApproved(r);
        log.info("报销单审批通过, reimbursementId={}", r.getId());

    } else if ("REJECTED".equals(event.getAction())) {
        r.setStatus("REJECTED");
        reimbursementMapper.updateById(r);
        eventPublisher.publishReimbursementRejected(r);
        log.info("报销单审批驳回, reimbursementId={}", r.getId());
    }
}
```

注意：`ApprovalCompletedEvent` 已在 `common/mq/event/ApprovalCompletedEvent.java` 中定义，审批服务和报销服务共用同一个类。不要各自定义。

**OCR 完成事件处理**：

```java
@RabbitListener(queues = MqConstants.QUEUE_OCR_COMPLETED)
public void onOcrCompleted(OcrCompletedEvent event) {
    // 更新 reimbursement_attachment.ocr_amount + ocr_status = SUCCESS
}

@RabbitListener(queues = MqConstants.QUEUE_OCR_FAILED)
public void onOcrFailed(OcrFailedEvent event) {
    // 标记 ocr_status = FAILED
}
```

### 8.6 状态机——合法转换路径

```java
public class ReimbursementStatusMachine {
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
        "DRAFT",    Set.of("PENDING"),
        "PENDING",  Set.of("APPROVED", "REJECTED", "DRAFT"),  // 撤回=PENDING→DRAFT
        "REJECTED", Set.of("DRAFT"),                            // 驳回后可修改后重提
        "APPROVED", Set.of("PAID"),
        "PAID",     Set.of()
    );

    public static boolean canTransition(String from, String to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
```

### 8.7 金额防篡改哈希

提交时（状态变为 PENDING）计算 HMAC-SHA256：

```java
String data = reimbursement.getId() + "|" + reimbursement.getTotalAmount().toPlainString()
            + "|" + reimbursement.getApplicantId() + "|" + LocalDateTime.now();
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(hmacKey.getBytes(), "HmacSHA256"));
String hash = Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes()));
reimbursement.setAmountHash(hash);
```

---

## 9. 独立验证方案——不依赖预算和审批

报销服务要调两个不存在的下游。不加 Mock，提交报销单这条主流程根本跑不通。

**方案**：用 `@Profile("mock")` 创建假的 Feign Bean，Spring 在 mock profile 激活时注入 Mock 替代真实 Feign 调用。

### 9.1 Mock 配置类（新建文件）

`config/MockFeignConfig.java`：

```java
@Configuration
@Profile("mock")
public class MockFeignConfig {

    @Bean
    @Primary
    public BudgetClient mockBudgetClient() {
        return new BudgetClient() {
            @Override
            public Result<BudgetClient.FreezeResponse> freeze(BudgetClient.FreezeRequest req) {
                BudgetClient.FreezeResponse resp = new BudgetClient.FreezeResponse();
                resp.setSuccess(true);
                resp.setAvailableAfterFreeze(new java.math.BigDecimal("50000"));
                resp.setControlStrategy("STRICT");
                resp.setMessage("Mock: 预算冻结成功");
                return Result.ok(resp);
            }
            @Override
            public Result<Void> consume(BudgetClient.ConsumeRequest req) {
                return Result.ok();
            }
            @Override
            public Result<Void> unfreeze(BudgetClient.UnfreezeRequest req) {
                return Result.ok();
            }
            @Override
            public Result<BudgetClient.AvailableResponse> getAvailable(Long deptId, String cat) {
                return Result.ok(null);
            }
        };
    }

    @Bean
    @Primary
    public ApprovalClient mockApprovalClient() {
        return new ApprovalClient() {
            @Override
            public Result<ApprovalClient.StartResponse> start(ApprovalClient.StartRequest req) {
                ApprovalClient.StartResponse resp = new ApprovalClient.StartResponse();
                resp.setInstanceId(999L);
                resp.setCurrentApprover("Mock审批人");
                resp.setCurrentApproverId(1L);
                return Result.ok(resp);
            }
            // ... getInstance, getPending 返回空即可
        };
    }
}
```

### 9.2 用 Mock 模式启动

```powershell
cd F:\project_007\costlink-reimbursement
mvn spring-boot:run -Dspring-boot.run.profiles=dev,mock
```

`dev,mock` 两个 profile 同时激活：dev 拉 Nacos 配置，mock 注入假 Feign 客户端。

### 9.3 独立验证的三条命令

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

# 1. 创建草稿
curl -X POST http://127.0.0.1:8081/api/reimbursements \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"测试报销","expenseType":"TRAVEL",
       "items":[{"category":"TRAVEL_TRANSPORT","amount":100.00}]}'

# 2. 提交（Mock Feign 返回成功）
curl -X POST http://127.0.0.1:8081/api/reimbursements/{id}/submit \
  -H "Authorization: Bearer $TOKEN"

# 3. 模拟审批完成（发一条 MQ 消息，或直接调内部消费者验证）
# 在 RabbitMQ 管理界面 http://127.0.0.1:15672 手动发一条 JSON 消息到 q.approval.completed：
# {"reimbursementId":{id},"action":"APPROVED"}
# 查看报销服务日志：应打印"报销单审批通过"
```

### 9.4 验证清单

| 验证项 | Mock 模式 | 说明 |
|-------|----------|------|
| 创建报销单 | ✅ 无需 Mock | 纯本地操作 |
| 查询/更新/删除 | ✅ 无需 Mock | 同上 |
| 提交报销单 | ✅ Mock Feign | Mock 返回冻结成功 + 审批链启动成功 |
| Saga 正常流程 | ✅ Mock Feign | 验证创建→冻结→启动审批→改状态全链 |
| Saga 补偿流程 | ✅ Mock Feign | Mock ApprovalClient 抛异常，验证 unfreeze 被调 |
| 审批结果事件消费 | ✅ 手动发 MQ | RabbitMQ 控制台手动发 ApproavlCompleted |
| OCR 结果事件消费 | ✅ 手动发 MQ | 同上 |

---

## 10. Nacos 配置

**Data ID**: `costlink-reimbursement-dev.yaml`

```yaml
server:
  port: 8081

spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/costlink_reimbursement?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true
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

costlink:
  reimbursement:
    max-expense-items: 20
    max-amount: 100000
    withdraw-timeout-hours: 2
    duplicate-submit-window: 30
  saga:
    submit-timeout: 30
    budget-freeze-timeout: 5
    approval-start-timeout: 5
```

**共享配置** `costlink-shared-dev.yaml` 提供 JWT 密钥、Redis、RabbitMQ 连接——bootstrap.yml 已引用，不需要在本服务重复写。

---

## 11. 编码规范

1. 返回值用 `Result<T>`，错误码用 `ErrorCode.REIMBURSEMENT_*`
2. 异常用 `throw new BusinessException(ErrorCode.xxx)`
3. Controller 不写业务逻辑
4. 日志带 `reimbursementId`、`userId`
5. 注入用 `@RequiredArgsConstructor` + `private final`
6. Feign 调用加 `@SentinelResource` 做熔断降级
7. MQ 消费者用 `manual acknowledge`，异常时 `nack` 不要 `reject`

---

## 12. 验证方法

**前置**：认证服务 + Gateway + Redis + RabbitMQ 必须跑着。预算和审批服务还没写——调 Feign 时会失败，但可以用 Mock 挡掉。

**启动**：

```powershell
cd F:\project_007\costlink-reimbursement
mvn spring-boot:run
```

**验证一：创建草稿**

```bash
# 先登录拿 Token
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

# 创建报销单
curl -X POST http://127.0.0.1:8080/api/reimbursements \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"测试报销","expenseType":"TRAVEL",
       "items":[{"category":"TRAVEL_TRANSPORT","amount":100.00,"receiptDate":"2026-07-01"}]}'
```

预期：200 + `data.id` + `data.status = "DRAFT"`

**验证二：查询详情**

```bash
curl http://127.0.0.1:8080/api/reimbursements/{id} \
  -H "Authorization: Bearer $TOKEN"
```

**验证三：分页查询**

```bash
curl "http://127.0.0.1:8080/api/reimbursements?page=1&size=10" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 13. 检查清单

- [ ] 5 个 Entity + 5 个 Mapper 全部写齐
- [ ] CRUD 接口全部可用（创建/查询/更新/删除/分页）
- [ ] 提交 Saga：创建 outbox → 调 BudgetClient → 调 ApprovalClient → 更新状态（开发时可用 Mock 替代）
- [ ] Saga 补偿：ApprovalClient 调用失败时调 BudgetClient.unfreeze()
- [ ] MQ 消费者：ApprovalCompleted 事件含状态校验（非 PENDING → nack 重试）
- [ ] MQ 消费者：OCR 完成事件回写到 attachment 表
- [ ] Outbox 定时任务：扫 READY 消息发 RabbitMQ
- [ ] Feign 调用加了 @SentinelResource
- [ ] `@EnableFeignClients(basePackages = "com.costlink.common.feign")` 启动类上
- [ ] 无 Token 访问被 Gateway 拦下（开发时直连 8081 可暂时绕过测试）
