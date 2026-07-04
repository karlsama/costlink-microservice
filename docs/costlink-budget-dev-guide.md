# costlink-budget 开发指南

> 面向实际开发，一份文档写完预算服务。不需要回头看其他文件。
> 2026-07-04

---

## 1. 你要做一个什么

**一句话**：预算服务管理预算的完整生命周期——编制、冻结（报销提交时）、消费（审批通过时）、解冻（驳回/撤回时）、预警。它被报销服务通过 Feign 调用，同时消费 MQ 事件来处理审批结果。

**你的上游**：报销服务通过 Feign 调 `POST /internal/budgets/freeze|consume|unfreeze`；前端调 `GET/POST /api/budgets/...` 做预算管理和查询。

**你的下游**：通知服务——当预算超限或预警时发布 MQ 事件。

与报销服务的最大不同：**预算服务不做编排**。它不调用其他服务，只响应请求和消费事件。复杂度集中在并发安全——冻结预算时多人同时提交报销不能超扣。

## 2. 依赖状态

| 输入 | 来源 | 状态 |
|-----|------|------|
| MySQL `costlink_budget` | `127.0.0.1:3306`，4 张表已建（budget/budget_line/change_log/alert_config） | ✅ |
| Nacos | bootstrap.yml（`127.0.0.1:8848`） | ✅ |
| Redis + Redisson | 共享配置 | ✅ 需显式启用 Redisson |
| RabbitMQ | 共享配置 | ✅ 发布预算预警事件 |

**不需要**：Feign 客户端（只有别人调你，你不调别人）、百度 API、审批服务代码。

---

## 3. 数据库——4 张表

全部在 `costlink_budget` 库中，init.sql 已建好：

```
budget               — 预算主表（department_id, fiscal_year, total_amount, status）
budget_line          — 预算明细（budget_id, category, used_amount, frozen_amount, version 乐观锁）
budget_change_log    — 变动流水（FREEZE/CONSUME/UNFREEZE, before_amount, after_amount）
budget_alert_config  — 预警配置（warning_threshold, notify_roles）
```

**核心防护**：`budget_line.version` 是乐观锁。冻结/消费/解冻的 UPDATE 必须用 `WHERE id=? AND version=?`，更新失败说明并发冲突，重试。

---

## 4. 你要实现的接口

### 4.1 对外接口（前端通过 Gateway 调）

| 方法 | 路径 | 说明 |
|-----|------|------|
| POST | `/api/budgets` | 创建预算 |
| PUT  | `/api/budgets/{id}` | 更新预算 |
| GET  | `/api/budgets/{id}` | 查询预算详情 |
| GET  | `/api/budgets/available?deptId=&category=` | 查询可用余额 |
| GET  | `/api/budgets/execute-report` | 预算执行报表 |
| POST | `/api/budgets/adjustments` | 预算调整申请 |

### 4.2 内部接口（Feign，报销服务调）

这是你独有的——其他服务通过 `BudgetClient` 调你，你要对着它实现。

| 方法 | 路径 | 对应 Feign 接口 |
|-----|------|---------------|
| POST | `/internal/budgets/freeze` | `BudgetClient.freeze()` |
| POST | `/internal/budgets/consume` | `BudgetClient.consume()` |
| POST | `/internal/budgets/unfreeze` | `BudgetClient.unfreeze()` |
| GET  | `/internal/budgets/available` | `BudgetClient.getAvailable()` |

`BudgetClient` 在 `com.costlink.common.feign.BudgetClient` 中已定义，**不要实现这个接口**（和认证服务一样的坑），只要 Controller 的路径和 DTO 字段对应上就行。

**接口契约速查**：

```java
// POST /internal/budgets/freeze — 报销提交时调
FreezeRequest { Long reimbursementId; Long departmentId; List<FreezeItem> items; }
FreezeItem   { String category; BigDecimal amount; }
FreezeResponse { Boolean success; BigDecimal availableAfterFreeze; String controlStrategy; String message; }

// POST /internal/budgets/consume — 审批通过时调
ConsumeRequest { Long reimbursementId; List<ConsumeItem> items; }

// POST /internal/budgets/unfreeze — 驳回/撤回时调
UnfreezeRequest { Long reimbursementId; }
```

### 4.3 MQ 消费者（接收报销事件）

| 事件（来自） | 队列 | 处理 |
|------------|------|------|
| `reimbursement.approved` | `q.reimbursement.approved` | 消费冻结金额：`frozen_amount-=x`，`used_amount+=x` |
| `reimbursement.rejected` | `q.reimbursement.rejected` | 解冻金额：`frozen_amount-=x` |

**幂等性保障**：消费前查 `budget_change_log` 是否已存在相同 `source_id + change_type` 的流水。如果存在说明已处理过，直接 ack 跳过。防止 MQ 重发导致重复扣减。

### 4.4 MQ 发布者（预算预警）

| 事件 | 路由键 | 触发条件 |
|-----|-------|---------|
| 预算冻结 | `budget.frozen` | 每次 freeze 成功后发布 |
| 预算超支 | `budget.exceeded` | 可用余额 < 5%（critical_threshold） |

通知服务订阅这些事件，推送给部门主管和财务。

---

## 5. 核心逻辑

### 5.1 冻结（并发最高，防护最重）

```java
@Transactional  // ← 必须：一个 FreezeRequest 内多条 item 全成功或全回滚
public FreezeResponse freeze(FreezeRequest request) {
    // 1. 找到该部门当前财年的有效预算
    Budget budget = budgetMapper.selectOne(
        new LambdaQueryWrapper<Budget>()
            .eq(Budget::getDepartmentId, request.getDepartmentId())
            .eq(Budget::getFiscalYear, Year.now().getValue())
            .eq(Budget::getStatus, "ACTIVE")
    );
    if (budget == null) {
        return FreezeResponse.fail("该部门无有效预算");
    }

    // 2. 部门级 Redis 锁
    String lockKey = "budget:lock:" + request.getDepartmentId();
    RLock lock = redissonClient.getLock(lockKey);

    try {
        if (!lock.tryLock(3, 10, TimeUnit.SECONDS)) {
            return FreezeResponse.fail("系统繁忙，请稍后重试");
        }

        for (FreezeItem item : request.getItems()) {
            // 1. 查预算明细（带乐观锁 version）
            BudgetLine line = budgetLineMapper.selectOne(
                new LambdaQueryWrapper<BudgetLine>()
                    .eq(BudgetLine::getCategory, item.getCategory())
                    .eq(BudgetLine::getBudgetId, findActiveBudget(...))
            );

            // 2. 计算可用余额
            BigDecimal available = line.getTotalAmount()
                .subtract(line.getUsedAmount())
                .subtract(line.getFrozenAmount());

            // 3. 硬控制：不足则拒绝
            if (available.compareTo(item.getAmount()) < 0) {
                lock.unlock();
                return FreezeResponse.fail("预算不足，可用: " + available.toPlainString());
            }

            // 4. 更新冻结金额（乐观锁）
            int rows = budgetLineMapper.update(null,
                new LambdaUpdateWrapper<BudgetLine>()
                    .eq(BudgetLine::getId, line.getId())
                    .eq(BudgetLine::getVersion, line.getVersion())    // ← 乐观锁
                    .setSql("frozen_amount = frozen_amount + " + item.getAmount())
                    .setSql("version = version + 1")
            );
            if (rows == 0) {
                throw new BusinessException(ErrorCode.BUDGET_FREEZE_FAILED);
            }

            // 5. 记录流水
            saveChangeLog(line.getId(), "FREEZE", item.getAmount(), request.getReimbursementId());
        }
    } finally {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
}
```

**为什么同时用 Redis 锁和 MySQL 乐观锁**：Redis 锁防并发请求同时算余额（都算出够用，都扣），乐观锁兜底（Redis 锁失效时 MySQL 层拒绝）。

**为什么 freeze 方法需要 @Transactional**：一个报销单可能包含多条费用明细（比如同时报销交通费和住宿费）。如果第一条成功、第二条失败，已冻结的金额必须回滚。`@Transactional` 保证一个请求内的所有冻结操作全成功或全回滚。两层防护。

### 5.2 解冻（从流水表反查原始冻结记录）

```java
public void unfreeze(UnfreezeRequest request) {
    // 从流水表查原始冻结记录 → 知道冻结了哪些科目和金额
    List<BudgetChangeLog> freezeLogs = changeLogMapper.selectList(
        new LambdaQueryWrapper<BudgetChangeLog>()
            .eq(BudgetChangeLog::getSourceId, request.getReimbursementId())
            .eq(BudgetChangeLog::getChangeType, "FREEZE")
    );
    if (freezeLogs.isEmpty()) return;  // 没有冻结过，幂等跳过

    for (BudgetChangeLog log : freezeLogs) {
        budgetLineMapper.update(null,
            new LambdaUpdateWrapper<BudgetLine>()
                .eq(BudgetLine::getId, log.getBudgetLineId())
                .eq(BudgetLine::getVersion, /* 乐观锁 version */)
                .setSql("frozen_amount = frozen_amount - " + log.getChangeAmount())
                .setSql("version = version + 1")
        );
        saveChangeLog(log.getBudgetLineId(), "UNFREEZE",
            log.getChangeAmount().negate(), request.getReimbursementId());
    }
}
```

### 5.3 消费（审批通过后冻结→已用）

与解冻同理，从流水表反查冻结记录。区别是消费后 `used_amount+=x`，而不仅是解冻。

**幂等性检查**：消费前先查 `budget_change_log WHERE source_id=? AND change_type='CONSUME'`。如果已有记录，直接 ack 跳过——MQ 可能重发同一事件。

---

## 6. 你要写的代码文件

```
costlink-budget/src/main/java/com/costlink/budget/
├── BudgetApplication.java                  ← 启动类（@MapperScan）
├── controller/
│   ├── BudgetController.java               ← 对外：/api/budgets/**
│   └── BudgetInternalController.java       ← 内部：/internal/budgets/**（Feign 接口实现）
├── service/
│   ├── BudgetService.java                  ← 接口
│   └── impl/
│       ├── BudgetServiceImpl.java          ← CRUD + 查询
│       └── BudgetFreezeServiceImpl.java    ← 冻结/消费/解冻核心
├── mapper/
│   ├── BudgetMapper.java
│   ├── BudgetLineMapper.java
│   ├── BudgetChangeLogMapper.java
│   └── BudgetAlertConfigMapper.java
├── entity/
│   ├── Budget.java
│   ├── BudgetLine.java
│   ├── BudgetChangeLog.java
│   └── BudgetAlertConfig.java
├── mq/
│   └── BudgetEventConsumer.java            ← 消费 reimbursement.approved / .rejected
└── config/
    ├── BudgetConfig.java                   ← JwtUtil Bean
    └── RedissonConfig.java                 ← Redisson 分布式锁
```

不需要 Mock 配置——预算服务不调用 Feign，没有 Mock 需求。

---

## 7. Nacos 配置

**Data ID**: `costlink-budget-dev.yaml`

```yaml
server:
  port: 8082

spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/costlink_budget?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10

mybatis-plus:
  type-aliases-package: com.costlink.budget.entity
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0

costlink:
  budget:
    default-control-strategy: STRICT
    warning-threshold: 20
    critical-threshold: 5
    flexible-transfer-enabled: false
  lock:
    freeze-lock-timeout: 10
    freeze-lock-wait: 3

redisson:
  single-server-config:
    address: "redis://127.0.0.1:6379"
    database: 1
    connection-pool-size: 16
    connection-minimum-idle-size: 8
```

**共享配置** `costlink-shared-dev.yaml` 提供 RabbitMQ 连接——bootstrap.yml 已引用。

---

## 8. 编码规范

1. 返回值 `Result<T>`，内部错误码用 `ErrorCode.BUDGET_*`
2. 异常用 `throw new BusinessException(ErrorCode.xxx)`
3. 冻结/消费/解冻的 UPDATE 必须带 `version` 乐观锁
4. 日志带 `reimbursementId`、`category`、`amount`
5. `@RequiredArgsConstructor` + `private final`，不用 `@Autowired`
6. MetaObjectHandler 已由 common 提供，不要自己写

---

## 9. 验证方法

**前置**：MySQL + Nacos + Redis + RabbitMQ 必须在线。报销服务和认证服务不需要跑——预算服务可以先独立跑起来验证 CRUD 和 Feign 接口。

**启动**：

```powershell
cd F:\project_007\costlink-budget
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

预算服务在 `http://127.0.0.1:8082`。

**验证一：创建预算（先在 CMD 里直接 INSERT 测试数据更方便）**

```sql
INSERT INTO costlink_budget.budget (id, fiscal_year, department_id, total_amount, status) VALUES
(1, 2026, 10, 100000.00, 'ACTIVE');

INSERT INTO costlink_budget.budget_line (id, budget_id, category, total_amount, control_strategy) VALUES
(10, 1, 'TRAVEL_TRANSPORT', 50000.00, 'STRICT'),
(11, 1, 'OFFICE', 30000.00, 'SOFT');
```

**验证二：Feign 接口 — 冻结预算（模拟报销服务调它）**

```bash
curl -X POST http://127.0.0.1:8082/internal/budgets/freeze \
  -H "Content-Type: application/json" \
  -d '{"reimbursementId":1,"items":[{"category":"TRAVEL_TRANSPORT","amount":1000.00}]}'
```

预期：200 + `success=true` + `availableAfterFreeze=49000`。查 MySQL：`frozen_amount` 增加了 1000。

**验证三：Feign 接口 — 预算不足被拒**

```bash
# 请求超过可用余额
curl -X POST http://127.0.0.1:8082/internal/budgets/freeze \
  -H "Content-Type: application/json" \
  -d '{"reimbursementId":2,"items":[{"category":"TRAVEL_TRANSPORT","amount":999999.00}]}'
```

预期：200 + `success=false` + `message` 含"预算不足"。

**验证四：Feign 接口 — 消费冻结金额**

```bash
curl -X POST http://127.0.0.1:8082/internal/budgets/consume \
  -H "Content-Type: application/json" \
  -d '{"reimbursementId":1,"items":[{"category":"TRAVEL_TRANSPORT","amount":1000.00}]}'
```

预期：200。查 MySQL：`frozen_amount` 减 1000，`used_amount` 加 1000。

**验证五：Feign 接口 — 解冻**

```bash
# 先冻结一笔
curl -X POST http://127.0.0.1:8082/internal/budgets/freeze \
  -H "Content-Type: application/json" \
  -d '{"reimbursementId":3,"items":[{"category":"OFFICE","amount":500.00}]}'

# 再解冻
curl -X POST http://127.0.0.1:8082/internal/budgets/unfreeze \
  -H "Content-Type: application/json" \
  -d '{"reimbursementId":3}'
```

预期：200。查 MySQL：`frozen_amount` 减 500。

**验证六：MQ 消费 — 审批通过事件（手动发消息）**

在 RabbitMQ 管理界面 `http://127.0.0.1:15672` → Queues → `q.reimbursement.approved` → Publish message：

```json
{"reimbursementId":1,"items":[{"category":"TRAVEL_TRANSPORT","amount":1000.00}]}
```

预期：预算服务日志打印消费记录。MySQL：`frozen_amount-=1000`，`used_amount+=1000`。

---

## 10. 检查清单

- [ ] 4 个 Entity + 4 个 Mapper
- [ ] `BudgetInternalController`：4 个内部 Feign 接口全部实现
- [ ] 冻结用 Redis 分布式锁 + MySQL 乐观锁双层防护
- [ ] 冻结失败时有业务消息告知原因（不是 500）
- [ ] 冻结/消费/解冻都写 `budget_change_log` 流水
- [ ] `UNIQUE KEY uk_budget_category (budget_id, category)` 不会插入重复科目
- [ ] Redisson 配置从 Nacos 读取，不在代码里硬编码
- [ ] MQ 消费者 idempotent（同一 reimbursementId 不重复处理）
- [ ] MetaObjectHandler 没有自己重复写（common 已提供）
