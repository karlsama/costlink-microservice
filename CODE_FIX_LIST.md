# CostLink 代码修复清单

> 基于全项目代码审查，2026-07-06

---

## 🔴 阻塞性故障（不修无法运行）

### #1 MQ 全部事件发不出去

**文件**: `costlink-reimbursement/.../mq/ReimbursementEventPublisher.java`

**问题**: 直接塞 `Reimbursement` 实体给 RabbitMQ，没有 `implements Serializable`，没有配置 `Jackson2JsonMessageConverter`。四个方法全部会抛 `IllegalArgumentException`。

**修复**:

**步骤 A** — 让 Reimbursement 实体实现 Serializable：

```java
// costlink-reimbursement/.../entity/Reimbursement.java
public class Reimbursement implements Serializable {
```

**步骤 B** — 在报销服务配置类中加 Jackson2JsonMessageConverter：

```java
// costlink-reimbursement/.../config/ReimbursementConfig.java 中加
@Bean
public Jackson2JsonMessageConverter messageConverter() {
    return new Jackson2JsonMessageConverter();
}
```

**或者**——不在实体上实现，改用 DTO 发事件（更标准）：

```java
// ReimbursementEventPublisher 中
Map<String, Object> payload = Map.of(
    "reimbursementId", r.getId(),
    "departmentId", r.getDepartmentId(),
    "totalAmount", r.getTotalAmount().toPlainString(),
    "items", ... // 查 expense_items 表拼出列表
);
rabbitTemplate.convertAndSend(..., payload);
```

选一个。步骤 A+B 最快，2 分钟搞定。

---

### #2 前端所有页面拿不到数据

**文件**: `costlink-frontend/src/api/request.js:29`

**问题**: `response => response.data` 返回 `Result<T>` 包装对象，页面代码把它当 `T` 用。预算仪表盘 `Array.isArray(res)` 永远 false。

**修复**: 拦截器再多解一层：

```js
response => {
  const body = response.data         // axios 的 data = Result 对象
  if (body && typeof body.code === 'number') {
    return body.data                 // Result.data = 真正的业务数据
  }
  return body
}
```

这样每个页面拿到的就是解包后的业务数据，不需要改任何视图文件。

---

### #3 预算消费者读错了字段名 — MQ 不给力

**文件**: `costlink-budget/.../mq/BudgetEventConsumer.java:36`

**问题**: `body.get("reimbursementId")` → 发布方 `Reimbursement` 实体主键叫 `id`，不是什么 `reimbursementId`。收到 true 消息后依然空报。

**修复**: 等 #1 修好之后自动解决——如果用了 Map payload 并且键名为 `reimbursementId`，消费者能对上。如果 #1 走 DTO 方式，把发布方的 payload key 定为 `reimbursementId`。

或者修改 BudgetEventConsumer 兼容两边：

```java
Long reimbursementId = body.containsKey("reimbursementId")
    ? Long.valueOf(body.get("reimbursementId").toString())
    : Long.valueOf(body.get("id").toString());
```

---

## 🟡 缺失 / 不一致（调用方崩溃）

### #4 ApprovalInternalController 缺少两个 Feign 接口实现

**文件**: `costlink-approval/.../controller/ApprovalInternalController.java`

**问题**: `ApprovalClient` 声明了 `getInstance()` 和 `getPending()`，Controller 只实现了 `start()`。谁调这两方法谁等 404。

**修复**: 补两套端点：

```java
@GetMapping("/instance/{instanceId}")
public Result<ApprovalClient.InstanceResponse> getInstance(@PathVariable Long instanceId) {
    return approvalService.getInstanceDetail(instanceId);
}

@GetMapping("/pending")
public Result<List<ApprovalClient.PendingItem>> getPending(@RequestParam Long approverId) {
    return approvalService.getPendingList(approverId);
}
```

Service 层对应的实现方法要一起补。

---

### #5 OCR getResult 参数匹配错误

**文件**: `costlink-ocr/.../controller/OcrInternalController.java:60`

**问题**: Feign 接口声明是 `@RequestParam Long attachmentId`（Query Parameter），Controller 写成了 `@RequestBody Map<String, Object>`（JSON 体）。调用方直接收到 400。

**修复**: Controller 改成跟 Feign 接口一致：

```java
@PostMapping("/result")
public Result<OcrClient.OcrResultDTO> getResult(@RequestParam Long attachmentId) {
    OcrClient.OcrResultDTO result = ocrService.getResult(attachmentId);
    if (result == null) return Result.fail(10401, "识别结果不存在");
    return Result.ok(result);
}
```

Service 的 `getResult` 方法签名同步调整，去掉 `fileHash` 参数或从 Redis 里反查。

---

## 🟢 安全性 / 环境问题

### #6 审批 Controller 从请求体取操作人

**文件**: `costlink-approval/.../controller/ApprovalController.java`

**问题**: `operatorId` 从请求体 JSON 拿，不是从 `X-User-Id` Header。任何登录用户可冒充别人审批。

**修复**: 换成 `UserContext`：

```java
Long operatorId = UserContext.getUserId();
```

---

### #7 HMAC 密钥硬编码

**文件**: `costlink-reimbursement/.../service/impl/ReimbursementServiceImpl.java:232`

**问题**: `"CostLink-HMAC-Key-2026"` 直接写在代码里。

**修复**: 配到 Nacos 共享配置中，注入：

```java
@Value("${costlink.amount-hash.hmac-key}")
private String hmacKey;
```

---

### #8 `.env` 有真实百度凭据

**文件**: `.env`

**问题**: 活着的 API Key 提交进了 Git。

**修复**: `.gitignore` 加 `.env`（如果还没加的话）。已经有了就不动。

---

### #9 Redisson 没配密码

**文件**: `costlink-budget/.../config/RedissonConfig.java`

**问题**: 硬编码 `redis://127.0.0.1:6379`，没密码。

**修复**: 从 Nacos 配置取：

```java
String address = "redis://" + redisHost + ":" + redisPort;
config.useSingleServer().setAddress(address);
config.useSingleServer().setPassword(redisPassword);
```

---

### #10 docker-compose.yml 构建不了

**问题**: 所有服务引用 `build: ./costlink-*` 但没 Dockerfile；nginx 挂载 `./nginx.conf` 但文件不存在。

**修复**: 开发阶段手动 IDE 启动，集成阶段再补 Dockerfile。低优先级。

---

## 修复优先级

| 顺序 | 编号 | 预估时间 | 理由 |
|-----|------|---------|------|
| 1 | #1 | 2 分钟 | MQ 是整个异步架构的血管 |
| 2 | #2 | 1 分钟 | 前端数据不显示 |
| 3 | #4 | 5 分钟 | Feign 接口不全 |
| 4 | #5 | 3 分钟 | OCR 参数匹配 |
| 5 | #3 | 自动修 | #1 修完后 #3 自动好 |
| 6 | #6 | 1 分钟 | 安全漏洞 |
| 7 | #7 | 2 分钟 | 硬编码密钥 |
| 8 | #8 | 30 秒 | 凭据保护 |
| 9 | #9 | 3 分钟 | Redisson 密码 |
| 10 | #10 | 后续 | 集成阶段的事 |
