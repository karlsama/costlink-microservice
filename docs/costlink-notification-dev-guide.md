# costlink-notification 开发指南

> 面向实际开发，一份文档写完通知服务。不需要回头看其他文件。
> 2026-07-04

---

## 1. 你要做一个什么

**一句话**：通知服务是一个纯 MQ 消费者。它订阅审批和预算事件，按消息模板渲染成通知文字，写进数据库的 `message` 表。前端轮询这个表展示"我的消息"。

**跟其他服务的区别**：没有人通过 Feign 或 HTTP 调你。你只消费 MQ 事件，不做响应。用独立数据库（`costlink_notification`），不调任何外部 API。

## 2. 依赖状态

| 输入 | 来源 | 状态 |
|-----|------|------|
| MySQL `costlink_notification` | 两张表已建（message + 5 条消息模板已注入） | ✅ |
| Nacos | bootstrap.yml | ✅ |
| RabbitMQ | 共享配置 | ✅ |

**不需要**：Feign 客户端、Feign 接口、Redis、分布式锁、外部凭据。

## 3. 数据库——2 张表

`costlink_notification.message` 存通知记录，`costlink_notification.message_template` 存模板（init.sql 已注入 5 条）：

| 模板编码 | 触发场景 |
|---------|---------|
| `APPROVAL_NOTIFY` | 新的待审批任务 |
| `APPROVAL_APPROVED` | 报销单通过 |
| `APPROVAL_REJECTED` | 报销单驳回 |
| `BUDGET_WARNING` | 预算低于阈值 |
| `PAYMENT_NOTIFY` | 报销款已支付 |

模板使用占位符（`{title}`、`{amount}`、`{reason}` 等），消费代码用 `String.replace` 替换为实际值。

## 4. MQ 消费者——你要订阅四个事件

| 事件 | 队列 | 做什么 |
|-----|------|-------|
| `approval.node.completed` | `q.approval.node.completed` | 通知下一审批人 → 你有一条待审批 |
| `approval.completed` (action=APPROVED) | `q.approval.completed` | 通知申请人 → 报销已通过 |
| `approval.completed` (action=REJECTED) | `q.approval.completed` | 通知申请人 → 报销被驳回 |
| `budget.exceeded` | `q.budget.exceeded` | 通知部门主管 → 预算不足 |
| `budget.frozen` | `q.budget.frozen` | 通知申请人 → 预算已冻结 |

```java
@RabbitListener(queues = MqConstants.QUEUE_APPROVAL_NODE_COMPLETED)
public void onNodeCompleted(/* JSON as Map */) {
    // 1. 解析事件 → 拿到 nextApproverId、reimbursementId、title
    // 2. 查模板 APPROVAL_NOTIFY
    // 3. 替换占位符 → 生成 title 和 content
    // 4. INSERT INTO message (user_id=nextApproverId, title, content, message_type, channel, related_id)
}
```

四个消费者的逻辑几乎一样：解析事件 → 查模板 → 替换占位符 → INSERT。

## 5. 开发阶段只开启站内信通道

通知可以走邮件、企微、钉钉、站内信四个渠道。开发阶段只开站内信——写 `message` 表就是全部工作。渠道切换在 Nacos 配置中控制：

```yaml
costlink:
  notification:
    channels:
      in-app:
        enabled: true
      email:
        enabled: false    # ← 生产才开
```

## 6. 你要写的代码文件

```
costlink-notification/src/main/java/com/costlink/notification/
├── NotificationApplication.java          ← @MapperScan
├── mq/
│   ├── ApprovalEventConsumer.java        ← 消费所有审批事件
│   └── BudgetEventConsumer.java          ← 消费预算预警事件
├── service/
│   ├── NotificationService.java          ← 接口
│   └── impl/NotificationServiceImpl.java ← 查模板 + 替换占位符 + 写入 message
├── mapper/
│   ├── MessageMapper.java
│   └── MessageTemplateMapper.java
├── entity/
│   ├── Message.java
│   └── MessageTemplate.java
└── config/
    └── NotificationConfig.java
```

总共 9 个文件。没有 Controller、没有 Feign 客户端。

## 7. Nacos 配置

```yaml
server:
  port: 8086

spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/costlink_notification?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:}

mybatis-plus:
  type-aliases-package: com.costlink.notification.entity

costlink:
  notification:
    channels:
      in-app:
        enabled: true
      email:
        enabled: false
    retention-days: 90
```

## 8. 验证方法

RabbitMQ 管理界面 `http://127.0.0.1:15672` → `q.approval.node.completed` → Publish message：

```json
{"reimbursementId":100,"title":"测试报销","amount":500.00,"nextApproverId":1,"nextApproverName":"管理员"}
```

查 MySQL：

```sql
SELECT * FROM costlink_notification.message ORDER BY create_time DESC LIMIT 5;
```

应该看到一条记录：`user_id=1`，`message_type=APPROVAL_NOTIFY`，`content` 包含"测试报销"和"500"。

## 9. 检查清单

- [ ] 2 个 Entity + 2 个 Mapper
- [ ] 4 个事件消费者全部订阅
- [ ] 消息模板查出来后正确替换占位符
- [ ] message 写入后 `send_status=PENDING`（dev 只写不推）
- [ ] 不认识的 eventType → 忽略（不炸服务）
- [ ] MQ 连接失败时不崩溃（rabbitmq 没启动时也能启动通知服务）
