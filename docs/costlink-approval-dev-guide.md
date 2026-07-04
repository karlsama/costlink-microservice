# costlink-approval 开发指南

> 面向实际开发，一份文档写完审批服务。不需要回头看其他文件。
> 2026-07-04

---

## 1. 你要做一个什么

**一句话**：审批服务是审批链引擎——报销单提交后，它按"金额→审批模板→审批人链"生成审批实例，管理每个审批节点的通过/驳回/转审，每到一个节点完成就通知下一审批人，全部通过则通知报销服务扣减预算。

**你的上游**：报销服务通过 Feign 调 `POST /internal/approvals/start` 启动审批链；前端调 `POST /api/approvals/{id}/approve|reject|transfer` 进行审批操作。

**你的下游**：认证服务（Feign 调用查审批人信息，看谁该审）；报销服务（MQ 事件通知审批完成，谁赢了）；通知服务（MQ 事件通知下一审批人）。

## 2. 依赖状态

| 输入 | 来源 | 状态 |
|-----|------|------|
| MySQL `costlink_approval` | `127.0.0.1:3306`，4 张表已建（含默认审批模板 id=1） | ✅ |
| Nacos | bootstrap.yml | ✅ |
| RabbitMQ | 共享配置 | ✅ 发布审批事件 |
| Redis | 共享配置 | ✅ |
| 认证服务 | `lb://costlink-auth`（Feign AuthClient） | ✅ 已开发 |

## 3. 数据库——4 张表

全部在 `costlink_approval` 库中。init.sql 建好了默认审批模板（id=1）：

```
approval_template     — 审批模板（rules JSON: 金额条件 → 角色审批人链）。已有 id=1 的标准模板
approval_instance     — 审批实例（一个报销单对应一个实例，存当前进度）
approval_node         — 审批节点（每个审批人一个节点，存自己的通过/驳回/转审状态）
approval_record       — 操作记录（每一次 approve/reject/transfer 都留下记录）
```

**默认模板 rules JSON 结构（id=1）**：

```json
[
  {
    "priority": 1,
    "condition": {"amountMin": 0, "amountMax": 1000},
    "approvers": [{"type": "ROLE", "value": "DEPARTMENT_HEAD", "mode": "SINGLE"}]
  },
  {
    "priority": 2,
    "condition": {"amountMin": 1000, "amountMax": 5000},
    "approvers": [
      {"type": "ROLE", "value": "DEPARTMENT_HEAD", "mode": "SINGLE"},
      {"type": "ROLE", "value": "FINANCE_MANAGER", "mode": "SINGLE"}
    ]
  },
  {
    "priority": 3,
    "condition": {"amountMin": 5000},
    "approvers": [
      {"type": "ROLE", "value": "DEPARTMENT_HEAD", "mode": "SINGLE"},
      {"type": "ROLE", "value": "FINANCE_MANAGER", "mode": "SINGLE"},
      {"type": "ROLE", "value": "ADMIN", "mode": "SINGLE"}
    ]
  }
]
```

含义：金额小于 1000 只需部门主管审批；1000 到 5000 需要主管+财务经理两级审批；大于 5000 再加一级管理员审批。

## 4. 你要实现的接口

### 4.1 对外接口（前端通过 Gateway 调）

| 方法 | 路径 | 说明 |
|-----|------|------|
| POST | `/api/approvals/{instanceId}/approve` | 审批通过 |
| POST | `/api/approvals/{instanceId}/reject` | 审批驳回 |
| POST | `/api/approvals/{instanceId}/transfer` | 转审（转给其他人） |
| GET  | `/api/approvals/pending?approverId=` | 我的待办列表 |
| GET  | `/api/approvals/instances/{id}` | 查询审批实例详情（含所有节点） |
| GET  | `/api/approvals/templates` | 审批模板管理 |

### 4.2 内部接口（Feign，报销服务调）

`ApprovalClient` 在 `com.costlink.common.feign.ApprovalClient` 中已定义。你来实现它对应的 Controller：

| 方法 | 路径 | 说明 |
|-----|------|------|
| POST | `/internal/approvals/start` | 启动审批链（报销提交时调） |

```java
// StartRequest
{ Long reimbursementId; Long applicantId; Long departmentId; BigDecimal totalAmount; String expenseType; }

// StartResponse
{ Long instanceId; String currentApprover; Long currentApproverId; List<NodeInfo> nodeChain; }
```

### 4.3 MQ 发布（不是消费——审批服务发别人）

| 事件 | 路由键 | 触发时机 |
|-----|-------|---------|
| 审批节点完成 | `approval.node.completed` | 一个审批人通过/驳回后 → 通知服务通知下一审批人 |
| 审批流程完成 | `approval.completed` | 全部通过或最终驳回 → **事件体含 action=APPROVED/REJECTED** |

**重要**：`approval.completed` 的 payload 必须包含 `action` 字段。这是之前审查报销服务时发现的——没有它，报销服务不知道要改 APPROVED 还是 REJECTED。

```json
{
  "reimbursementId": 10086,
  "instanceId": 200,
  "action": "APPROVED"
}
```

### 4.4 Feign 调用方（审批服务调别人）

| 接口 | 用途 | 调用时机 |
|-----|------|---------|
| `AuthClient.getUserById()` | 拿审批人姓名 | 生成节点时 |
| `AuthClient.getUsersByRole("DEPARTMENT_HEAD", deptId)` | 按角色查审批人 | 解析审批模板时，role→具体的人 |

**Mock 策略**：认证服务已经开发完毕，开发审批服务时它应该已经在跑。如果认证服务不在线，`start()` 会调 Feign 失败——这时候可以临时用跟报销服务一样的 `@Profile("mock")` 方案注入假的 AuthClient。新建 `config/MockAuthFeignConfig.java`：

```java
@Configuration
@Profile("mock")
public class MockAuthFeignConfig {
    @Bean @Primary
    public AuthClient mockAuthClient() {
        return new AuthClient() {
            @Override
            public Result<UserInfoDTO> getUserById(Long id) {
                UserInfoDTO u = new UserInfoDTO();
                u.setId(id); u.setDisplayName("Mock审批人");
                u.setRole("DEPARTMENT_HEAD"); u.setDepartmentId(10L);
                return Result.ok(u);
            }
            @Override
            public Result<List<UserInfoDTO>> getUsersByRole(String role, Long deptId) {
                return Result.ok(List.of(/* 同上 */));
            }
            @Override
            public Result<List<UserInfoDTO>> getUsersByDepartment(Long deptId) {
                return Result.ok(List.of());
            }
        };
    }
}
```

## 5. 核心逻辑——启动审批链

这是最复杂的方法。`reimbursement` 调 `start()`，你要做的事：

```
start(StartRequest req)
  │
  ├─ 1. 加载审批模板（默认 id=1，或根据 expenseType 匹配）
  │
  ├─ 2. 评估 rules JSON → 找到匹配报销金额的条件
  │     ├─ 金额 500  → 命中 priority=1 → 审批人: [DEPARTMENT_HEAD]
  │     ├─ 金额 3000 → 命中 priority=2 → 审批人: [DEPARTMENT_HEAD, FINANCE_MANAGER]
  │     └─ 金额 8000 → 命中 priority=3 → 审批人: [DEPARTMENT_HEAD, FINANCE_MANAGER, ADMIN]
  │
  ├─ 3. 对每个 approver：
  │     ├─ 调 AuthClient.getUsersByRole(role, departmentId) → 拿到具体人员列表
  │     ├─ 如果审批人 == 申请人且 autoSkipSelf=true → 跳过（标记 SKIPPED）
  │     └─ 生成 approval_node 记录
  │
  ├─ 4. 创建 approval_instance（存 reimbursementId + 节点总数 + 当前节点序号=1）
  │
  ├─ 5. 发布 approval.node.completed → 通知第一个审批人
  │
  └─ 6. 返回 StartResponse（instanceId + 当前审批人信息 + 完整节点链）
```

**跳过自己**：如果申请人是部门主管自己，审批链里 "DEPARTMENT_HEAD" 这个节点标记 SKIPPED。如果全部节点都被跳过（单节点且申请人与审批人同人），审批实例直接标记 APPROVED，立即发布 `approval.completed(action=APPROVED)`——不要让报销单卡在没有审批人的状态。

## 6. 核心逻辑——审批操作

**审批通过**（`POST /api/approvals/{instanceId}/approve`）：

```
1. 校验：操作人 == 当前节点的 approverId
2. 更新 node.status = APPROVED, action = APPROVE
3. 写 approval_record
4. 判断是否最后一个节点：
   ├─ 是 → instance.status = APPROVED
   │        发布 approval.completed (action=APPROVED)
   │        通知申请人"报销已通过"
   └─ 否 → instance.current_node_order++
           发布 approval.node.completed
           通知下一审批人
```

**审批驳回**：

1. 校验操作人
2. 更新 node.status = REJECTED
3. instance.status = REJECTED（驳回是终局——不需要后面的节点审了）
4. 发布 approval.completed (action=REJECTED)

**转审**：

1. 当前节点标记 TRANSFERRED
2. 创建新节点（同一个 node_order，新的 approverId）
3. 通知新审批人

## 7. 你要写的代码文件

```
costlink-approval/src/main/java/com/costlink/approval/
├── ApprovalApplication.java                ← @MapperScan + @EnableFeignClients
├── controller/
│   ├── ApprovalController.java             ← 对外接口
│   └── ApprovalInternalController.java     ← start() Feign 接口实现
├── service/
│   ├── ApprovalService.java                ← 接口
│   ├── impl/ApprovalServiceImpl.java       ← 审批操作（approve/reject/transfer）
│   └── ApprovalChainEngine.java            ← 审批链引擎（评估模板+生成节点）
├── mapper/ (×4)
│   ├── ApprovalTemplateMapper.java
│   ├── ApprovalInstanceMapper.java
│   ├── ApprovalNodeMapper.java
│   └── ApprovalRecordMapper.java
├── entity/ (×4)
│   ├── ApprovalTemplate.java
│   ├── ApprovalInstance.java
│   ├── ApprovalNode.java
│   └── ApprovalRecord.java
├── mq/
│   └── ApprovalEventPublisher.java         ← 发布 node.completed + completed
├── config/
│   ├── ApprovalConfig.java                 ← JwtUtil Bean
│   └── FeignClientConfig.java              ← @EnableFeignClients(@Profile("!mock"))
└── dto/
    └── ApprovalCompletedEvent.java         ← 事件 payload（含 action 字段）
```

---

## 8. Nacos 配置

**Data ID**: `costlink-approval-dev.yaml`

```yaml
server:
  port: 8083

spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/costlink_approval?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10

mybatis-plus:
  type-aliases-package: com.costlink.approval.entity
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0

costlink:
  approval:
    default-timeout-hours: 72
    chain-engine:
      max-nodes: 10
      auto-skip-self: true
    notification:
      on-node-start: true
      on-complete: true
      on-reject: true
      on-reminder: true
```

---

## 9. 编码规范

1. 返回值 `Result<T>`，错误码 `ErrorCode.APPROVAL_*`
2. 异常用 `throw new BusinessException(ErrorCode.xxx)`
3. 审批模板 rules JSON 解析用 Jackson 转对象，不要手写 JSON 字符串拼拆
4. `authClient.getUsersByRole()` 返回的是该部门该角色的用户列表——可能有多个（比如一个部门两个副主管）
5. `ApprovalCompletedEvent` 必须含 `action` 字段（APPROVED / REJECTED）
6. MQ 事件体里 `reimbursementId` 和 `instanceId` 都要有——报销服务用前者，通知服务用后者
7. MetaObjectHandler 已由 common 提供，不要自己写

---

## 10. 验证方法

**前置**：MySQL + Nacos + RabbitMQ + 认证服务（查审批人用）必须在线。

**启动**：

```powershell
cd F:\project_007\costlink-approval
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

审批服务在 `http://127.0.0.1:8083`。

**验证一：启动审批链（模拟报销服务调你）**

```bash
curl -X POST http://127.0.0.1:8083/internal/approvals/start \
  -H "Content-Type: application/json" \
  -d '{"reimbursementId":1,"applicantId":1,"departmentId":10,
       "totalAmount":500.00,"expenseType":"TRAVEL"}'
```

预期：200 + `instanceId` + `currentApprover` + `nodeChain`。金额 500 命中 priority=1 → 只有一个 DEPARTMENT_HEAD 节点。如果 applicantId=1 且审批人也是 1，autoSkipSelf 跳过 → `nodeChain` 为空或第一个节点 SKIPPED。

**验证二：审批通过**

```bash
curl -X POST http://127.0.0.1:8083/api/approvals/{instanceId}/approve \
  -H "Content-Type: application/json" \
  -d '{"action":"APPROVE","comment":"同意"}'
```

预期：200。最后一个节点通过后 → RabbitMQ 管理界面 `http://127.0.0.1:15672` 的 `q.approval.completed` 里应该有一条消息，action=APPROVED。

**验证三：我的待办**

```bash
curl http://127.0.0.1:8083/api/approvals/pending?approverId=1
```

预期：200 + 列表。

**验证四：驳回**

```bash
# 先重新 start 一个新实例
curl -X POST http://127.0.0.1:8083/api/approvals/{instanceId}/reject \
  -H "Content-Type: application/json" \
  -d '{"action":"REJECT","comment":"发票不清楚"}'
```

预期：200。instance 状态变为 REJECTED，approval.completed 事件 action=REJECTED。

---

## 11. 检查清单

- [ ] 4 个 Entity + 4 个 Mapper
- [ ] `start()` 能根据金额选择正确的审批模板规则
- [ ] `start()` 调 `AuthClient.getUsersByRole()` 把角色转为具体人员
- [ ] 申请人 == 审批人时 autoSkip 生效
- [ ] approve 最后一个节点后 instance 状态改 APPROVED
- [ ] approve 非最后一个节点后 current_node_order++，通知下一节点
- [ ] reject 直接终止实例，不继续后续节点
- [ ] 每次操作写入 approval_record
- [ ] 每次节点完成发布 approval.node.completed
- [ ] 全部通过/驳回时发布 approval.completed，含 action 字段
- [ ] `FeignClientConfig` 标注 `@Profile("!mock")`
- [ ] MetaObjectHandler 不自己写
