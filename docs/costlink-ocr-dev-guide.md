# costlink-ocr 开发指南

> 面向实际开发，一份文档写完 OCR 服务。不需要回头看其他文件。
> 2026-07-04

---

## 1. 你要做一个什么

**一句话**：OCR 服务封装百度增值税发票识别 API。它接收票据图片，调百度 API 做识别，把结果通过 MQ 事件发回报销服务。同时维护一套缓存——相同图片不重复识别。

**跟其他服务最大的区别**：OCR 没有数据库。没有表、没有 Mapper、没有 Entity、没有 MyBatis-Plus。存储只用到 Redis（缓存识别结果和百度 AccessToken）。

**你的上游**：报销服务（Feign 调 `POST /internal/ocr/recognize-async` 或 MQ 消费 `reimbursement.submitted`）。

**你的下游**：报销服务（MQ 事件 `ocr.completed` / `ocr.failed` 回写结果）。没有其他服务依赖 OCR。

## 2. 提前准备

**外部凭据（必须提前配好）**：

| 凭据 | 位置 | 状态 |
|-----|------|------|
| 百度 AppId / API Key / Secret Key | `.env` 文件 | ✅ 已配，之前用 curl 验证过可换取 AccessToken |

OCR 服务是从环境变量读的——Nacos 配置里用 `${BAIDU_OCR_API_KEY:}` 占位符。Docker 模式下从 `.env` 注入；IDE 直接启动时需要在启动参数或系统环境变量中设好这三个值。

**你的基础设施**：

| 依赖 | 用途 |
|-----|------|
| Redis | 缓存识别结果（图片 MD5 → OcrResult），缓存百度 AccessToken |
| RabbitMQ | 消费 `reimbursement.submitted`，发布 `ocr.completed` / `ocr.failed` |

**不需要**：没有 MySQL、没有数据库、没有 MyBatis-Plus、没有 Feign 消费方。

---

## 3. 你要实现的接口

### 3.1 对外接口（OcrClient Feign，报销服务调）

`OcrClient` 在 `com.costlink.common.feign.OcrClient` 中已定义。对着它实现。

| 方法 | 路径 | 说明 |
|-----|------|------|
| POST | `/internal/ocr/recognize-async` | 异步识别——立即返回 200，MQ 回写结果 |
| POST | `/internal/ocr/recognize` | 同步识别——等待百度返回结果再响应 |
| POST | `/internal/ocr/result` | 查询 attachment 的识别结果 |

### 3.2 MQ 消费

| 事件 | 队列 | 做什么 |
|-----|------|-------|
| `reimbursement.submitted` | `q.reimbursement.submitted` | 收到新报销单 → 遍历其附件 → 对每张图片调百度 OCR → 发 `ocr.completed` / `ocr.failed` 事件回报销服务 |

### 3.3 MQ 发布

| 事件 | 路由键 | 时机 |
|-----|-------|------|
| OCR 完成 | `ocr.completed` | 百度返回合法结果 |
| OCR 失败 | `ocr.failed` | 百度报错 / 图片格式不支持 / 额度用尽 |

---

## 4. 核心逻辑

### 4.1 百度 API 调用流程

```
1. 从 Redis 读取 AccessToken（key: baidu:ocr:access_token）
   ├─ 有且未过期 → 直接用
   └─ 无或快过期 → POST oauth/2.0/token → 换取新Token → 存 Redis（TTL=29天）

2. 预处理图片（压缩到 <4MB，格式校验）

3. 计算图片 MD5 → 查 Redis 缓存
   ├─ 命中 → 直接返回缓存结果（省钱、不浪费免费额度）
   └─ 未命中 → 继续

4. 图片 Base64 编码 → POST rest/2.0/ocr/v1/vat_invoice

5. 解析百度返回 → 存 Redis 缓存（24小时） → 返回 OcrResult
```

### 4.2 AccessTokenManager

```java
@Component
public class AccessTokenManager {
    private static final String TOKEN_KEY = "baidu:ocr:access_token";
    private static final long REFRESH_BEFORE = 24 * 60 * 60; // 提前1天刷新

    private final StringRedisTemplate redis;
    private final BaiduOcrProperties props;

    public String getAccessToken() {
        String cached = redis.opsForValue().get(TOKEN_KEY);
        if (cached != null) return cached;
        return refreshToken();
    }

    @Scheduled(fixedDelay = 3600_000) // 每小时检查一次
    public void refreshIfNeeded() {
        Long ttl = redis.getExpire(TOKEN_KEY);
        if (ttl == null || ttl < REFRESH_BEFORE) refreshToken();
    }

    private String refreshToken() {
        // POST https://aip.baidubce.com/oauth/2.0/token
        //   ?grant_type=client_credentials
        //   &client_id={API_KEY}
        //   &client_secret={SECRET_KEY}
        String response = restTemplate.postForObject(...);
        String token = parseAccessToken(response);
        redis.opsForValue().set(TOKEN_KEY, token, 29, TimeUnit.DAYS);
        return token;
    }
}
```

### 4.3 缓存策略

```
Redis Key 设计:
  ocr:result:{md5hash}         → OcrResult JSON   TTL=24h
  ocr:invoice:{invoiceNumber}  → md5hash          TTL=永久
  baidu:ocr:access_token       → token string     TTL=29天
```

**去重逻辑**：同一张发票可能被同一个人上传两次（手滑）。先按 MD5 查缓存——命中直接返回。再按发票号码查——同一张发票即使文件不同（截图 vs 拍照），识别出的发票号相同，也能避免重复识别。

### 4.4 引擎切换（策略模式）

```java
public interface OcrEngine {
    OcrResult recognize(byte[] imageBytes);
}

@Component
@ConditionalOnProperty(name = "costlink.ocr.engine", havingValue = "baidu", matchIfMissing = true)
public class BaiduOcrEngine implements OcrEngine { ... }

@Component
@ConditionalOnProperty(name = "costlink.ocr.engine", havingValue = "paddle")
public class PaddleOcrEngine implements OcrEngine { ... }
```

策略模式保证未来换 OCR 引擎时不需要改任何调用代码——只切换一个配置值。

---

## 5. 你要写的代码文件

```
costlink-ocr/src/main/java/com/costlink/ocr/
├── OcrApplication.java              ← @EnableScheduling（定时刷新Token）
├── controller/
│   └── OcrInternalController.java   ← 实现 OcrClient 的三个接口路径
├── engine/
│   ├── OcrEngine.java               ← 接口（策略模式）
│   ├── BaiduOcrEngine.java          ← 百度 OCR 实现 ★ 核心
│   ├── PaddleOcrEngine.java         ← 预留（空壳）
│   └── TesseractOcrEngine.java      ← 预留（空壳）
├── service/
│   └── OcrService.java              ← 缓存查询 + MQ 发布
├── mq/
│   ├── OcrEventConsumer.java        ← 消费 reimbursement.submitted
│   └── OcrEventPublisher.java        ← 发布 ocr.completed / ocr.failed
├── config/
│   ├── OcrConfig.java               ← RestTemplate Bean
│   ├── BaiduOcrProperties.java      ← @ConfigurationProperties(prefix="costlink.ocr.baidu")
│   └── AccessTokenManager.java      ← Token 管理 ★
└── dto/
    └── BaiduOcrResponse.java        ← 百度 API JSON → Java 对象
```

总共约 10 个文件。没有 Entity、没有 Mapper、没有 Repository。

---

## 6. Nacos 配置

**Data ID**: `costlink-ocr-dev.yaml`

```yaml
server:
  port: 8085

costlink:
  ocr:
    engine: baidu
    baidu:
      base-url: https://aip.baidubce.com
      app-id: ${BAIDU_OCR_APP_ID:}
      api-key: ${BAIDU_OCR_API_KEY:}
      secret-key: ${BAIDU_OCR_SECRET_KEY:}
      token-refresh-before-expire: 86400
      connect-timeout: 5000
      read-timeout: 15000
      daily-free-quota: 500
    preprocessor:
      max-size: 4194304
      supported-formats: [JPEG, PNG, BMP]
      compression-quality: 0.8
    cache:
      result-ttl-hours: 24
    rate-limit:
      max-per-second: 10
```

Redis 连接从共享配置 `costlink-shared-dev.yaml` 取。

---

## 7. 编码规范

1. 百度 AccessToken 绝对不能硬编码在代码里，必须从 Redis 或环境变量读
2. 图片 Base64 编码前先压缩，直接传原图可能超时
3. 百度返回结果必须缓存——免费额度每天 500 次，同一图片不要重复识别
4. credential 不在日志中打印（API Key、Secret Key 不能用 log.info 打出来）
5. MQ 消费者处理失败时 nack（让 MQ 重试），不要吞掉异常
6. MetaObjectHandler 不涉及（没有数据库）

---

## 8. 验证方法

**前置**：MySQL 不用、其他服务不用。只需要 Redis + RabbitMQ + 百度凭据已在环境变量中。

**启动**：

```powershell
cd F:\project_007\costlink-ocr
# 需要设百度凭据环境变量
$env:BAIDU_OCR_API_KEY="7rkfAoOJffUKw2aLmjgIYFJo"
$env:BAIDU_OCR_SECRET_KEY="QRt53HFQSrkajHDsWjDE8YWw8ffTILon"
$env:BAIDU_OCR_APP_ID="123874271"
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

**验证一：同步识别（直调百度 API）**

```bash
# 找一张发票图片 test_invoice.jpg，Base64 编码后发
BASE64=$(base64 -w 0 test_invoice.jpg)
curl -X POST http://127.0.0.1:8085/internal/ocr/recognize \
  -H "Content-Type: application/json" \
  -d "{\"attachmentId\":1,\"fileHash\":\"$(md5sum test_invoice.jpg | cut -d' ' -f1)\",\"fileUrl\":\"data:image/jpeg;base64,$BASE64\"}"
```

预期：200 + `totalAmount` + `invoiceNumber` 等结构化字段。

**验证二：异步识别**

```bash
curl -X POST http://127.0.0.1:8085/internal/ocr/recognize-async \
  -H "Content-Type: application/json" \
  -d '{"attachmentId":1,"reimbursementId":100,"fileUrl":"...","fileHash":"abc"}'
```

预期：200（无结果体）。在 RabbitMQ 管理界面 `q.ocr.completed` 应有一条消息。

**验证三：缓存命中**

同一步再次调用 `/recognize`（同样 MD5）。检查日志——第二次不应再有百度 API 的 HTTP 调用记录。预期 200 + 相同结果。

**验证四：MQ 事件消费**

在 RabbitMQ 管理界面往 `q.reimbursement.submitted` 发一条消息（模拟报销服务提交后的事件）。观察 OCR 服务日志——应消费消息并尝试识别。

---

## 9. 检查清单

- [ ] AccessTokenManager 能自动获取和刷新百度 Token
- [ ] 百度 API 调用成功返回结构化 OCR 结果
- [ ] 相同图片 MD5 再次识别时命中 Redis 缓存，不调百度
- [ ] 异步识别返回 200 后 MQ 事件成功发布
- [ ] 同步识别等待百度返回后再响应
- [ ] 百度凭据不在日志中打印
- [ ] 无数据库依赖，无 MyBatis-Plus 配置
- [ ] 三种引擎可通过配置切换（目前只实现百度即可）
