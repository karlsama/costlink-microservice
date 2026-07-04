# CostLink 代码审查问题清单

> 日期: 2026-07-05 | 来源: 全项目代码审查
> 共 10 个问题，4 严重、3 高、2 中、1 低

---

## 🔴 严重（核心业务流断裂，必须修）

### #1 预算冻结判错——硬控制永远不生效

**文件**: `costlink-reimbursement/.../service/impl/ReimbursementServiceImpl.java:198`

**代码**:
```java
if (!freezeResult.isSuccess()) {  // ← 永远为 true
```

`Result.isSuccess()` 判断的是 `code == 200`。预算服务始终返回 `Result.ok(...)`，即使冻结失败（预算不足）也返回 200。实际成功/失败在 `freezeResult.getData().getSuccess()`。

**修复**:
```java
if (freezeResult.getData() == null || !freezeResult.getData().getSuccess()) {
```

**后果**: 预算不足时照样提交报销单进入审批，硬控制永远不生效。

---

### #2 审批完成后没发消息通知预算——冻结→已用的链路断了

**文件**: `costlink-reimbursement/.../mq/ReimbursementEventConsumer.java:33-53`

**问题**: `onApprovalCompleted()` 只改了报销单状态，没调 `ReimbursementEventPublisher.publishReimbursementApproved()` 或 `.publishReimbursementRejected()`。

**修复**: 在 APPREHEND 分支后加：
```java
eventPublisher.publishReimbursementApproved(r);
```
在 REJECTED 分支后加：
```java
eventPublisher.publishReimbursementRejected(r);
```

ReimbursementEventPublisher 这两个方法已经写好了，只是没被调用。

**后果**: 审批通过的报销单，预算永远冻结着不消费。审批驳回的报销单，预算解冻不了。

---

### #3 ApprovalCompletedEvent 缺一半字段

**文件**: `costlink-approval/.../mq/RabbitApprovalEventPublisher.java:42-43`

**代码**:
```java
ApprovalCompletedEvent event = new ApprovalCompletedEvent(
    inst.getReimbursementId(), inst.getId(), action);
// title=null, amount=null, applicantId=null
```

**修复**: 改成填满六个字段：
```java
ApprovalCompletedEvent event = new ApprovalCompletedEvent(
    inst.getReimbursementId(), inst.getId(), action,
    inst.getTitle(),          // ← 如果 instance 没存 title，从 reimbursement 表查或从 StartRequest 传
    inst.getTotalAmount(),
    inst.getApplicantId()
);
```

**后果**: 通知服务拿不到收件人 ID，审批结果通知永远发不出去。

---

### #4 ApprovalNodeCompletedEvent 字段名全错

**文件**: `costlink-approval/.../mq/RabbitApprovalEventPublisher.java:30-36`

**代码**:
```java
Map.of("instanceId", ..., "approverId", ..., "approverName", ...)
```

事件类 `ApprovalNodeCompletedEvent` 的字段名是 `nextApproverId`、`nextApproverName`。

**修复**: Map 的 key 改成事件类字段名：
```java
Map.of("instanceId", ...,
       "reimbursementId", ...,
       "nextApproverId", ...,
       "nextApproverName", ...)
```

或者直接用 `ApprovalNodeCompletedEvent` 对象代替 Map。

**后果**: 下一个审批人永远收不到待办通知。

---

## 🟡 高（功能不正常但不完全断裂）

### #5 ApprovalCompletedEvent 重复定义

| 版本 | 路径 | 字段数 |
|-----|------|-------|
| common | `com.costlink.common.mq.event.ApprovalCompletedEvent` | 6 |
| reimbursement 本地 | `com.costlink.reimbursement.dto.event.ApprovalCompletedEvent` | 3 |

**修复**: 删掉 reimbursement 本地那份，`ReimbursementEventConsumer.java` 的 import 改为 common 版本。

---

### #6 通知消费者读错了字段名

**文件**: `costlink-notification/.../mq/ApprovalEventConsumer.java:56`

**代码**:
```java
body.get("totalAmount").toString()
```

`ApprovalCompletedEvent` 的字段叫 `amount`，不是 `totalAmount`。总是读到 null。

**修复**: 改为 `body.get("amount")`。

---

### #7 金额防篡改哈希生成后没验证

**文件**: `costlink-reimbursement/.../service/impl/ReimbursementServiceImpl.java:228,307-318`

`submit()` 方法里生成了 `amountHash` 并写入数据库，但全系统没有任何地方在审批或付款前校验它。

**修复**: 审批操作前校验（可选，后续补），或在 CHANGELOG 里标注待补。

---

## 🟢 中低

### #8 通知消费者 String vs 事件对象

**文件**: `costlink-notification/.../mq/ApprovalEventConsumer.java:34`

消费者声明 `String message` 参数，发布方发的是类型化对象。依赖于 AMQP 的消息转换器配置。

**修复**: 消费者参数改为具体事件类型 `ApprovalCompletedEvent event`。

---

### #9 Raw Type 泛型丢失

**文件**: `costlink-budget/.../controller/BudgetInternalController.java:39`

```java
return (Result) budgetService.getAvailable(departmentId, category);
```

**修复**: 改为 `Result<BudgetClient.AvailableResponse>`。

---

### #10 @Version 乐观锁与手动 version 增量并存

**文件**: `costlink-budget/.../service/impl/BudgetFreezeServiceImpl.java:90-96`

`BudgetLine` 实体有 `@Version` 注解，方法里又手动 `.setSql("version = version + 1")`。MyBatis-Plus 会再增一次。

**修复**: 去掉手动增量，让 `@Version` 自动处理；或者去掉 `@Version` 注解保留手动逻辑。不要两套同时。
