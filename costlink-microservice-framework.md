# CostLink 微服务架构设计框架

> **基于**: CostLink 单体架构 → 微服务架构演进
> **拆分策略**: 核心业务拆分（报销服务 + 预算服务 + 审批服务 + 共享基础设施）
> **OCR引擎**: 百度OCR API
> **版本**: v2.0-Microservice
> **日期**: 2026-07-01

---

## 目录

1. [架构演进概述](#1-架构演进概述)
2. [微服务全景架构](#2-微服务全景架构)
3. [服务边界与职责](#3-服务边界与职责)
4. [技术栈定义](#4-技术栈定义)
5. [服务间通信设计](#5-服务间通信设计)
6. [数据架构与一致性策略](#6-数据架构与一致性策略)
7. [API网关设计](#7-api网关设计)
8. [百度OCR集成方案](#8-百度ocr集成方案)
9. [安全架构设计](#9-安全架构设计)
10. [可观测性设计](#10-可观测性设计)
11. [部署架构](#11-部署架构)
12. [关键业务流程设计](#12-关键业务流程设计)
13. [项目结构与模块规划](#13-项目结构与模块规划)

---

## 1. 架构演进概述

### 1.1 为什么从单体走向微服务

原 CostLink 单体架构（Spring Boot 3.x + Vue 3 + MySQL + Docker Compose）在以下场景下面临瓶颈：

| 单体痛点 | 微服务解法 |
|---------|-----------|
| 报销高峰期（月底集中提交）需要整体扩容，连带预算查询、报表模块一起扩，浪费资源 | 仅扩容报销服务实例，预算和报表保持原有规模 |
| 预算模块的变更（预算调整逻辑）与报销模块耦合在同一次发布中，不敢独立上线 | 预算服务独立部署，变更风险隔离 |
| 审批流程频繁调整（加审批节点、改条件分支）影响报销核心逻辑的稳定性 | 审批服务独立演进，审批链变更不影响报销提单 |
| OCR识别是CPU密集型操作，与报销业务共享线程池，高峰期互相抢占资源 | OCR作为共享基础设施独立管理线程和连接池 |
| 数据库越来越大，报销表、预算表、审批记录混在同一个库，备份恢复时间长 | 每个服务独立Schema，各自治管理 |

### 1.2 拆分原则

采用 **DDD限界上下文（Bounded Context）** 作为拆分依据：

```
┌─────────────────────────────────────────────────────────────┐
│  单体 CostLink                                               │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐           │
│  │ 报销    │ │ 预算    │ │ 审批    │ │ 认证    │           │
│  │ 模块    │ │ 模块    │ │ 模块    │ │ OCR     │  ...      │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘           │
└─────────────────────────────────────────────────────────────┘
                          │ 按限界上下文拆分
                          ▼
┌─────────────────────────────────────────────────────────────┐
│  微服务 CostLink                                             │
│                                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                  │
│  │ 报销服务  │  │ 预算服务  │  │ 审批服务  │   ← 核心业务域  │
│  │ (独立DB)  │  │ (独立DB)  │  │ (独立DB)  │                  │
│  └──────────┘  └──────────┘  └──────────┘                  │
│                                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │ 认证中心  │  │ OCR服务  │  │ 通知服务  │  │ 报表服务  │   │
│  │ (共享)    │  │ (共享)    │  │ (共享)    │  │ (共享)    │   │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘   │
└─────────────────────────────────────────────────────────────┘
```

核心业务域三个服务各司其职、独立部署、独立数据库。认证、OCR、通知、报表四者作为共享基础设施，以公共库（common-lib）或内聚模块的形式嵌入各业务服务，未来可按需独立为微服务。

---

## 2. 微服务全景架构

### 2.1 整体架构图

```
                              ┌─────────────────────────┐
                              │      前端 SPA             │
                              │   Vue 3 + Element Plus   │
                              │   (Nginx 静态托管)        │
                              └───────────┬─────────────┘
                                          │ HTTPS
                                          ▼
                              ┌─────────────────────────┐
                              │    API Gateway           │
                              │  Spring Cloud Gateway    │
                              │  ├─ 路由转发             │
                              │  ├─ 限流（Sentinel）     │
                              │  ├─ 认证鉴权（JWT校验）  │
                              │  ├─ 请求日志             │
                              │  └─ 跨域处理             │
                              └───────────┬─────────────┘
                                          │
          ┌───────────┬───────────┬───────┼───────┬───────────┬───────────┐
          │           │           │       │       │           │           │
          ▼           ▼           ▼       │       ▼           ▼           ▼
    ┌─────────┐ ┌─────────┐ ┌─────────┐  │  ┌─────────┐ ┌─────────┐ ┌─────────┐
    │ 报销    │ │ 预算    │ │ 审批    │  │  │ Auth    │ │ OCR     │ │ 通知    │
    │ 服务    │ │ 服务    │ │ 服务    │  │  │ 认证    │ │ 识别    │ │ 推送    │
    │ :8081   │ │ :8082   │ │ :8083   │  │  │ :8084   │ │ :8085   │ │ :8086   │
    └────┬────┘ └────┬────┘ └────┬────┘  │  └────┬────┘ └────┬────┘ └────┬────┘
         │           │           │       │       │           │           │
         │    ┌──────┴──────┐    │       │       │           │           │
         │    │ 消息队列     │◄───┼───────┼───────┼───────────┼───────────┘
         │    │ RabbitMQ    │    │       │       │           │
         │    └──────┬──────┘    │       │       │           │
         │           │           │       │       │           │
         ▼           ▼           ▼       │       ▼           ▼
    ┌─────────┐ ┌─────────┐ ┌─────────┐  │  ┌─────────────────────┐
    │MySQL    │ │MySQL    │ │MySQL    │  │  │   Nacos 注册中心     │
    │报销库    │ │预算库    │ │审批库    │  │  │   配置中心           │
    │:3307    │ │:3308    │ │:3309    │  │  └─────────────────────┘
    └─────────┘ └─────────┘ └─────────┘  │
                                          │  ┌─────────────────────┐
    ┌─────────────────────────────────────┘  │ Elasticsearch +     │
    │                                        │ Kibana (日志)       │
    ▼                                        │ SkyWalking (链路)   │
    ┌─────────┐                              │ Prometheus (指标)   │
    │ Redis   │                              └─────────────────────┘
    │ 缓存/锁  │
    │ :6379   │
    └─────────┘
```

### 2.2 服务清单

| 服务名称 | 服务ID | 端口 | 数据库 | 部署实例 | 说明 |
|---------|--------|------|--------|---------|------|
| API网关 | costlink-gateway | 8080 | — | 2+ | 统一入口，路由、限流、鉴权 |
| 报销服务 | costlink-reimbursement | 8081 | reimbursement_db | 2+ | 报销单CRUD、费用明细、附件管理 |
| 预算服务 | costlink-budget | 8082 | budget_db | 2 | 预算编制、执行监控、预警 |
| 审批服务 | costlink-approval | 8083 | approval_db | 2+ | 审批链引擎、审批记录、转审/加签 |
| 认证服务 | costlink-auth | 8084 | 共享user库 | 2 | 登录认证、JWT签发、用户管理 |
| OCR服务 | costlink-ocr | 8085 | — | 2+ | 百度OCR API封装、结果缓存 |
| 通知服务 | costlink-notification | 8086 | notification_db | 1 | 邮件/企微/钉钉/站内信推送 |
| 报表服务 | costlink-report | 8087 | 只读副本 | 1 | 统计分析、报表生成、数据导出 |

---

## 3. 服务边界与职责

### 3.1 报销服务 (costlink-reimbursement)

**核心职责**: 报销单的全生命周期管理（草稿→提交→审批→付款），除审批流转外。

**拥有数据**:

```
reimbursement          — 报销单主表
expense_item           — 费用明细行
reimbursement_attachment — 票据附件关联
payment_record         — 付款记录
收款账户信息            — 员工支付宝/银行卡（引用user_id）
```

**对外暴露接口**:

| 方法 | 路径 | 说明 |
|-----|------|------|
| POST | `/api/reimbursements` | 创建报销单（草稿） |
| PUT | `/api/reimbursements/{id}` | 更新报销单 |
| GET | `/api/reimbursements/{id}` | 查询报销单详情 |
| GET | `/api/reimbursements?page=&status=` | 分页查询报销列表 |
| POST | `/api/reimbursements/{id}/submit` | 提交报销单 → 触发审批链 |
| POST | `/api/reimbursements/{id}/withdraw` | 撤回报销单 |
| POST | `/api/reimbursements/{id}/mark-paid` | 标记已付款 |
| DELETE | `/api/reimbursements/{id}` | 删除草稿（逻辑删除） |

**不负责的事**:
- 审批流程路由 → 调审批服务
- 预算扣减 → 发事件给预算服务
- 票据金额识别 → 调OCR服务
- 用户认证 → 依赖网关层JWT

### 3.2 预算服务 (costlink-budget)

**核心职责**: 预算编制、预算执行监控、预算预警、超支控制。

**拥有数据**:

```
budget                 — 预算主表（部门/项目 + 财年）
budget_line            — 预算明细（分科目）
budget_change_log      — 预算变动流水
budget_alert_config    — 预警配置
budget_adjustment      — 预算调整申请单
```

**对外暴露接口**:

| 方法 | 路径 | 说明 |
|-----|------|------|
| POST | `/api/budgets` | 创建预算 |
| PUT | `/api/budgets/{id}` | 更新预算 |
| GET | `/api/budgets/{id}` | 查询预算详情 |
| GET | `/api/budgets/available?deptId=&category=` | 查询可用余额 |
| POST | `/api/budgets/adjustments` | 预算调整申请 |
| GET | `/api/budgets/execute-report` | 预算执行报表 |

**内部接口（Feign调用，不对外暴露）**:

| 方法 | 路径 | 说明 |
|-----|------|------|
| POST | `/internal/budgets/freeze` | 冻结金额（报销提交时） |
| POST | `/internal/budgets/consume` | 消费金额（审批通过时） |
| POST | `/internal/budgets/unfreeze` | 解冻金额（审批驳回/撤回时） |
| GET | `/internal/budgets/check` | 预算可用性检查 |

**不负责的事**:
- 报销单数据 → 只接收事件，不持有报销详情
- 审批流程 → 不管

### 3.3 审批服务 (costlink-approval)

**核心职责**: 审批链模板管理、审批实例生命周期、审批记录。

**拥有数据**:

```
approval_template      — 审批链模板（条件→审批人规则）
approval_instance      — 审批实例（与报销单1:1关联）
approval_record        — 审批记录（每一步操作）
approval_node          — 审批节点快照（当前实例的节点状态）
```

**对外暴露接口**:

| 方法 | 路径 | 说明 |
|-----|------|------|
| POST | `/api/approvals/start` | 启动审批流程（报销服务调用） |
| POST | `/api/approvals/{instanceId}/approve` | 审批通过 |
| POST | `/api/approvals/{instanceId}/reject` | 审批驳回 |
| POST | `/api/approvals/{instanceId}/transfer` | 转审 |
| POST | `/api/approvals/{instanceId}/add-sign` | 加签 |
| GET | `/api/approvals/instances/{id}` | 查询审批实例详情 |
| GET | `/api/approvals/pending?approverId=` | 我的待办 |
| GET | `/api/approvals/history?reimbursementId=` | 报销单审批历史 |
| GET | `/api/approvals/templates` | 审批模板管理 |

**不负责的事**:
- 报销单数据 → 只存 reimbursement_id 引用
- 用户组织架构 → 调认证服务获取审批人

### 3.4 共享基础设施

#### 认证服务 (costlink-auth)

| 职责 | 说明 |
|-----|------|
| 登录认证 | 用户名密码 / LDAP / OAuth2.0 |
| JWT签发与刷新 | 双Token机制（Access Token 30min + Refresh Token 7d） |
| 用户管理 | CRUD、角色分配、部门关联 |
| 权限数据 | 提供用户角色、部门归属查询（Feign内部接口） |

JWT Token 在 Gateway 层校验，各业务服务不再重复校验，仅从 Token 中解析 userId / role / departmentId。

#### OCR服务 (costlink-ocr)

| 职责 | 说明 |
|-----|------|
| 百度OCR API封装 | Access Token管理、API调用、结果解析 |
| OCR结果缓存 | 相同图片hash的结果缓存（Redis），避免重复调用 |
| 多引擎切换 | 预留接口适配层，未来可切换PaddleOCR / 腾讯OCR |
| 票据字段解析 | 从OCR原始结果中提取金额、日期、发票号码等关键字段 |

#### 通知服务 (costlink-notification)

| 职责 | 说明 |
|-----|------|
| 消息通道管理 | 邮件、企业微信、钉钉、站内信 |
| 消息模板 | 审批通知、驳回通知、预算预警、付款通知 |
| 消息发送 | 订阅 RabbitMQ 事件，按模板渲染后发送 |
| 已读/未读 | 站内信状态管理 |

#### 报表服务 (costlink-report)

| 职责 | 说明 |
|-----|------|
| 统计报表 | 报销统计、预算执行率、部门费用排行 |
| 只读数据源 | 连接各业务库的只读副本，不直接修改业务数据 |
| 数据导出 | Excel / PDF 导出 |

---

## 4. 技术栈定义

### 4.1 各服务统一技术栈

| 层次 | 技术选型 | 版本 | 说明 |
|-----|---------|------|------|
| **语言** | Java | 17 (LTS) | 所有服务统一 |
| **框架** | Spring Boot | 3.2.x | — |
| **Spring Cloud** | Spring Cloud | 2023.0.x (Leyton) | 对应 Boot 3.2 |
| **服务注册/配置** | Nacos | 2.3.x | 替代 Eureka + Config |
| **API网关** | Spring Cloud Gateway | 4.1.x | 基于WebFlux，非阻塞 |
| **熔断降级** | Sentinel | 1.8.x | 集成Gateway和Feign |
| **远程调用** | OpenFeign | 4.1.x | 声明式HTTP客户端 |
| **负载均衡** | Spring Cloud LoadBalancer | — | 替代Ribbon |
| **消息队列** | RabbitMQ | 3.12.x | 事件驱动通信 |
| **ORM** | MyBatis-Plus | 3.5.x | — |
| **数据库** | MySQL | 8.0 | 每服务独立Schema |
| **缓存** | Redis | 7.x | 共享集群 |
| **认证** | Spring Security + JWT | — | Gateway统一鉴权 |
| **日志** | Logback → Elasticsearch | — | 结构化JSON日志 |
| **链路追踪** | Micrometer Tracing + SkyWalking | — | — |
| **指标监控** | Spring Boot Actuator + Prometheus | — | — |
| **容器** | Docker + Docker Compose (dev) | — | 生产可迁移K8s |

### 4.2 引入的新中间件说明

| 中间件 | 原单体未使用 | 引入原因 |
|-------|------------|---------|
| **Nacos** | ✗ | 服务注册发现 + 统一配置管理，微服务基础设施 |
| **RabbitMQ** | ✗ | 异步解耦：审批结果通知预算扣减、OCR结果回写、消息推送 |
| **Sentinel** | ✗ | 防止级联故障：OCR服务慢时不拖垮报销服务 |
| **Elasticsearch + Kibana** | ✗ | 集中日志：8个服务的日志分散在各容器，必须聚合查询 |
| **SkyWalking** | ✗ | 链路追踪：一次报销提交跨越3-4个服务，需要看到完整调用链 |

---

## 5. 服务间通信设计

### 5.1 通信策略矩阵

不同场景采用不同的通信模式：

| 场景 | 模式 | 实现 | 原因 |
|-----|------|------|------|
| 报销提交 → 启动审批 | 同步 RPC | OpenFeign | 需要立即返回审批链信息和当前审批人 |
| 报销提交 → 预算冻结 | 同步 RPC | OpenFeign | 需要立即知道预算是否充足（硬控制场景） |
| 提交 → OCR票据识别 | 异步消息 | RabbitMQ | OCR耗时长(1-3秒)，不应阻塞提单响应 |
| 审批通过 → 预算扣减 | 异步消息 | RabbitMQ | 预算扣减不需要同步返回，可异步 |
| 审批驳回 → 预算解冻 | 异步消息 | RabbitMQ | 同上 |
| 审批节点完成 → 通知下一审批人 | 异步消息 | RabbitMQ | 通知发送不影响审批主流程 |
| 报销状态变更 → 通知申请人 | 异步消息 | RabbitMQ | 同上 |
| 预算余额不足 → 预警通知 | 异步消息 | RabbitMQ | — |
| 认证服务 → 用户信息查询 | 同步 RPC | OpenFeign | 审批服务需要查询审批人信息 |

**核心原则**: 能在后台做的事就不用同步等，但涉及用户交互结果的（能不能提交、谁审批）必须同步返回。

### 5.2 RabbitMQ 消息定义

**交换机与队列设计**:

```
Exchange: costlink.reimbursement (topic)
├── Queue: reimbursement.submitted  → 预算服务(冻结)、OCR服务(识别)
├── Queue: reimbursement.approved   → 预算服务(扣减)、报表服务
├── Queue: reimbursement.rejected   → 预算服务(解冻)
└── Queue: reimbursement.paid       → 通知服务、报表服务

Exchange: costlink.approval (topic)
├── Queue: approval.node.completed  → 通知服务(通知下一审批人)
└── Queue: approval.completed       → 报销服务(状态更新)

Exchange: costlink.budget (topic)
├── Queue: budget.frozen            → 通知服务(预占通知申请人)
├── Queue: budget.exceeded          → 通知服务(预算预警)
└── Queue: budget.adjusted          → 报表服务

Exchange: costlink.ocr (topic)
├── Queue: ocr.completed            → 报销服务(回写OCR结果到attachment表)
└── Queue: ocr.failed               → 报销服务(标记OCR失败)
```

**消息体结构规范**:

```json
{
  "messageId": "uuid",
  "eventType": "REIMBURSEMENT_SUBMITTED",
  "timestamp": "2026-07-01T10:30:00.000+08:00",
  "source": "costlink-reimbursement",
  "payload": {
    "reimbursementId": 10086,
    "applicantId": 42,
    "departmentId": 10,
    "totalAmount": 2780.00,
    "items": [
      {"category": "TRAVEL_TRANSPORT", "amount": 1580.00},
      {"category": "TRAVEL_HOTEL", "amount": 1200.00}
    ]
  }
}
```

### 5.3 OpenFeign 接口定义示例

```java
// 报销服务调用预算服务的Feign客户端
@FeignClient(name = "costlink-budget", path = "/internal/budgets")
public interface BudgetClient {

    @PostMapping("/freeze")
    Result<FreezeResponse> freeze(@RequestBody FreezeRequest request);

    @PostMapping("/consume")
    Result<Void> consume(@RequestBody BudgetConsumeRequest request);

    @PostMapping("/unfreeze")
    Result<Void> unfreeze(@RequestBody BudgetUnfreezeRequest request);
}

// 预算服务内部接口实现
@RestController
@RequestMapping("/internal/budgets")
public class BudgetInternalController {

    @PostMapping("/freeze")
    public Result<FreezeResponse> freeze(@RequestBody FreezeRequest request) {
        // 使用分布式锁（Redis）保证并发安全
        // 检查可用余额 → 扣除 → 记录流水
        // 返回冻结结果和预算状态
    }
}
```

### 5.4 容错与降级

| 依赖服务 | 故障场景 | 降级策略 |
|---------|---------|---------|
| 预算服务不可用 | 报销提交时调用冻结失败 | **硬控制模式下拒绝提交**，提示"预算服务暂不可用"；软控制下降级放行 |
| 审批服务不可用 | 报销提交时无法启动审批链 | **直接拒绝提交**，审批服务是关键路径，不可降级 |
| OCR服务不可用 | 上传票据后无法识别 | 降级为"手动输入金额"模式，允许用户直接填金额 |
| 通知服务不可用 | 消息发送失败 | 忽略，审批流程不受影响。消息入死信队列等待重试 |

Sentinel 配置示例：

```yaml
# 报销服务中 Feign 调用的熔断配置
spring:
  cloud:
    sentinel:
      datasource:
        feign-rules:
          nacos:
            server-addr: nacos:8848
            data-id: costlink-reimbursement-sentinel
            rule-type: degrade
```

```json
// Sentinel 降级规则（存储在Nacos配置中）
[
  {
    "resource": "POST:/internal/budgets/freeze",
    "grade": 0,
    "count": 3,
    "timeWindow": 30,
    "minRequestAmount": 5,
    "statIntervalMs": 1000
  }
]
// 含义：1秒内 freeze 接口异常比例超过0.3（30%），则熔断30秒
```

---

## 6. 数据架构与一致性策略

### 6.1 数据库拆分策略

```
┌─────────────────────────────────────────────────────────────┐
│                    MySQL 8.0 集群                            │
│                                                              │
│  ┌──────────────────┐  ┌──────────────────┐                 │
│  │ reimbursement_db │  │ budget_db         │                 │
│  │ ├─ reimbursement │  │ ├─ budget         │                 │
│  │ ├─ expense_item  │  │ ├─ budget_line    │                 │
│  │ ├─ attachment    │  │ ├─ change_log     │                 │
│  │ └─ payment_record│  │ └─ alert_config   │                 │
│  └──────────────────┘  └──────────────────┘                 │
│                                                              │
│  ┌──────────────────┐  ┌──────────────────┐                 │
│  │ approval_db      │  │ shared_user_db   │                 │
│  │ ├─ approval_tpl  │  │ ├─ user          │                 │
│  │ ├─ approval_inst │  │ ├─ department    │                 │
│  │ ├─ approval_node │  │ ├─ role          │                 │
│  │ └─ approval_rec  │  │ └─ user_role     │                 │
│  └──────────────────┘  └──────────────────┘                 │
│                                                              │
│  ┌──────────────────┐  ┌──────────────────┐                 │
│  │ notification_db  │  │ 只读副本（报表用）│                 │
│  │ ├─ message       │  │ 各库的Read Replica│                 │
│  │ └─ template      │  └──────────────────┘                 │
│  └──────────────────┘                                        │
└─────────────────────────────────────────────────────────────┘
```

**关键约束**:
- 每个服务**只能直接读写自己的数据库**，跨库查询必须通过Feign调用
- 报表服务连接只读副本，避免分析查询影响在线业务
- 共享 user_db 由认证服务管理，其他服务通过 `/internal/users/{id}` 查询用户信息，不直接跨库 JOIN

### 6.2 分布式数据一致性 —— Saga模式

报销提交是企业财务场景，不能丢数据也不能多扣钱。这里采用 **编排式 Saga + 本地消息表** 保证最终一致性：

#### 场景一：报销提交（正向流程）

```
    ┌──────────────────────────────────────────────┐
    │              报销服务（编排者）                 │
    │                                               │
    │  1. 创建报销单（状态=DRAFT）                   │
    │  2. 调用预算服务 → 冻结金额                     │
    │     ├─ 成功 → 3                                │
    │     └─ 失败 → 返回"预算不足"给用户              │
    │  3. 调用审批服务 → 启动审批链                   │
    │     ├─ 成功 → 4                                │
    │     └─ 失败 → 补偿：调用预算解冻 → 返回错误      │
    │  4. 更新报销单状态=PENDING                     │
    │  5. 发送 ReimbursementSubmitted 事件           │
    └──────────────────────────────────────────────┘
```

```java
// 报销服务的 Saga 编排代码示意
@Service
public class ReimbursementSubmitSaga {

    @Transactional  // 仅本服务本地事务
    public SubmitResult execute(SubmitRequest request) {
        // Step 1: 本地事务 — 创建报销单 + 本地消息表
        Reimbursement reimbursement = createReimbursement(request);
        OutboxMessage outbox = createOutboxMessage(reimbursement);

        // Step 2: 远程调用 — 预算冻结（同步，必须成功）
        FreezeResponse freezeResp = budgetClient.freeze(
            new FreezeRequest(reimbursement.getId(), request.getItems())
        );
        if (!freezeResp.isSuccess()) {
            return SubmitResult.fail("预算不足: " + freezeResp.getMessage());
        }

        try {
            // Step 3: 远程调用 — 启动审批链
            ApprovalStartResp approvalResp = approvalClient.start(
                new ApprovalStartRequest(reimbursement.getId(), ...)
            );

            // Step 4: 本地事务 — 更新状态
            reimbursement.setStatus("PENDING");
            reimbursement.setApprovalInstanceId(approvalResp.getInstanceId());
            reimbursementMapper.updateById(reimbursement);

            // Step 5: 发布事件（通过本地消息表保证发送）
            outbox.setStatus("READY");
            outboxMapper.updateById(outbox);

            return SubmitResult.success(reimbursement);

        } catch (Exception e) {
            // 补偿: 解冻预算
            budgetClient.unfreeze(new BudgetUnfreezeRequest(reimbursement.getId()));
            throw new SagaCompensationException("审批服务调用失败，已回滚", e);
        }
    }
}
```

#### 场景二：审批结果处理（事件驱动）

```
审批人操作
    │
    ▼
审批服务 更新审批状态
    │
    ├─ 不是最终节点 → 通知下一审批人（异步）
    │
    └─ 是最终节点 且 通过
        │
        ▼
     发送 ApprovalCompleted 事件到 RabbitMQ
        │
        ├──→ 报销服务消费: 更新报销单状态 = APPROVED
        │
        └──→ 预算服务消费: 冻结金额 → 已用金额
                            （冻结减、已用加，余额不变）
```

### 6.3 本地消息表（Transactional Outbox）

保证"数据库写入"和"消息发送"的原子性：

```sql
-- 每个业务服务都有自己的 outbox 表
CREATE TABLE outbox_message (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id   VARCHAR(64) NOT NULL UNIQUE,
    aggregate_id BIGINT NOT NULL,        -- 聚合根ID（如报销单ID）
    event_type   VARCHAR(100) NOT NULL,  -- REIMBURSEMENT_SUBMITTED
    payload      JSON NOT NULL,           -- 消息体
    status       VARCHAR(20) DEFAULT 'PENDING', -- PENDING → SENT
    create_time  DATETIME NOT NULL,
    sent_time    DATETIME,
    retry_count  INT DEFAULT 0,
    INDEX idx_status_create (status, create_time)
);
```

由定时任务（或 Debezium CDC）扫描 `PENDING` 状态的消息，发送到 RabbitMQ，成功后更新为 `SENT`。这样即使 RabbitMQ 暂时不可用，消息也不会丢失。

### 6.4 分布式锁方案

预算冻结是高并发场景（月底多人同时提交报销），必须避免超扣。使用 Redis 分布式锁：

```java
// 预算服务的冻结方法
@PostMapping("/freeze")
public Result<FreezeResponse> freeze(@RequestBody FreezeRequest request) {
    String lockKey = "budget:lock:" + request.getDepartmentId()
                     + ":" + request.getCategory();

    RLock lock = redissonClient.getLock(lockKey);
    try {
        // 等待最多3秒，锁持有最多10秒
        if (!lock.tryLock(3, 10, TimeUnit.SECONDS)) {
            return Result.fail("系统繁忙，请稍后重试");
        }

        // 1. 查询当前可用余额
        BudgetLine line = budgetLineMapper.selectForUpdate(
            request.getBudgetId(), request.getCategory()
        );

        // 2. 检查余额（硬控制）
        BigDecimal available = line.getTotalAmount()
            .subtract(line.getUsedAmount())
            .subtract(line.getFrozenAmount());

        if (available.compareTo(request.getAmount()) < 0) {
            return Result.fail("预算不足，可用: " + available);
        }

        // 3. 扣减冻结金额
        line.setFrozenAmount(line.getFrozenAmount().add(request.getAmount()));
        budgetLineMapper.updateById(line);

        // 4. 记录流水
        saveChangeLog(line.getId(), "FREEZE", request.getAmount(),
                       request.getReimbursementId());

        return Result.success(new FreezeResponse(available.subtract(request.getAmount())));

    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Result.fail("系统繁忙");
    } finally {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

**预算表需要乐观锁兜底**（防止Redis锁失效的极端情况）：

```sql
-- budget_line 表使用 version 字段
UPDATE budget_line
SET frozen_amount = frozen_amount + #{amount},
    version = version + 1
WHERE id = #{id}
  AND version = #{version}
  AND (total_amount - used_amount - frozen_amount) >= #{amount}
```

---

## 7. API网关设计

### 7.1 Spring Cloud Gateway 架构

```
                         ┌─────────────────────────┐
                         │    Spring Cloud Gateway  │
                         │                          │
    Client Request ─────►│  ┌─────────────────────┐ │
                         │  │ GatewayFilter Chain  │ │
                         │  │                      │ │
                         │  │ 1. RequestSizeFilter │ │  限流后才能进入业务
                         │  │ 2. RateLimitFilter   │ │  根据IP+用户
                         │  │ 3. JwtAuthFilter     │ │  解析验证JWT
                         │  │ 4. RouteToService    │ │  路由到对应服务
                         │  │                      │ │
                         │  └─────────────────────┘ │
                         └───────────┬─────────────┘
                                     │
                     ┌───────────────┼───────────────┐
                     ▼               ▼               ▼
              报销服务:8081    预算服务:8082    审批服务:8083
```

### 7.2 路由配置

```yaml
# gateway application.yml
spring:
  cloud:
    gateway:
      routes:
        # 认证服务 — 无需鉴权
        - id: costlink-auth
          uri: lb://costlink-auth
          predicates:
            - Path=/api/auth/**
          filters:
            - StripPrefix=0

        # 报销服务 — 需要鉴权
        - id: costlink-reimbursement
          uri: lb://costlink-reimbursement
          predicates:
            - Path=/api/reimbursements/**
          filters:
            - name: JwtAuthentication
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 100
                redis-rate-limiter.burstCapacity: 200

        # 预算服务
        - id: costlink-budget
          uri: lb://costlink-budget
          predicates:
            - Path=/api/budgets/**
          filters:
            - name: JwtAuthentication

        # 审批服务
        - id: costlink-approval
          uri: lb://costlink-approval
          predicates:
            - Path=/api/approvals/**
          filters:
            - name: JwtAuthentication

        # OCR服务
        - id: costlink-ocr
          uri: lb://costlink-ocr
          predicates:
            - Path=/api/ocr/**
          filters:
            - name: JwtAuthentication

      # 不对外暴露内部接口
      default-filters:
        - DedupeResponseHeader=Access-Control-Allow-Origin

      discovery:
        locator:
          enabled: true
          lower-case-service-id: true
```

**内部接口隔离**: `/internal/**` 路径不在Gateway路由表中，只能在服务网格内部通过Feign调用，外部请求无法穿透Gateway到达内部接口。

### 7.3 JWT认证过滤器（Gateway层）

```java
@Component
public class JwtAuthenticationGatewayFilterFactory
        extends AbstractGatewayFilterFactory<Object> {

    @Override
    public GatewayFilter apply(Object config) {
        return (exchange, chain) -> {
            String token = extractToken(exchange.getRequest());
            if (token == null) {
                return unauthorized(exchange, "缺少认证令牌");
            }

            try {
                Claims claims = jwtUtil.parseToken(token);
                // 将用户信息写入请求头，下游服务直接读取
                ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header("X-User-Id", claims.get("userId").toString())
                    .header("X-User-Role", claims.get("role").toString())
                    .header("X-Department-Id", claims.get("departmentId").toString())
                    .build();

                return chain.filter(exchange.mutate().request(mutatedRequest).build());

            } catch (JwtException e) {
                return unauthorized(exchange, "令牌无效或已过期");
            }
        };
    }
}
```

下游服务通过 `RequestContextHolder` 工具类读取用户信息，不再重复验证JWT。

---

## 8. 百度OCR集成方案

### 8.1 为什么选百度OCR

| 对比维度 | Tesseract（原方案） | 百度OCR API |
|---------|-------------------|-------------|
| 中文票据识别准确率 | 60-75%（需大量调参） | 95%+（预训练模型） |
| 部署成本 | 需自建Tesseract镜像+中文语言包 | 零部署，API调用 |
| 增值税发票关键字段提取 | 不支持，需自己写后处理 | 内置发票结构化识别 |
| 成本 | 免费 | 按量付费（每天免费500次） |
| 响应速度 | 100-500ms | 200-800ms（网络） |

对于企业财务报销系统，准确率是第一位。百度的增值税发票识别API能直接返回发票号码、日期、金额、税额、销方名称等结构化数据，省去大量后处理工作。

### 8.2 OCR服务内部设计

```
┌──────────────────────────────────────────────────────────────┐
│                     costlink-ocr 服务                         │
│                                                               │
│  ┌──────────────────┐    ┌──────────────────┐                │
│  │ OCR Controller   │    │ Internal API     │                │
│  │ POST /api/ocr/   │    │ POST /internal/  │                │
│  │   recognize      │    │   ocr/batch      │                │
│  └────────┬─────────┘    └────────┬─────────┘                │
│           │                       │                           │
│           ▼                       ▼                           │
│  ┌────────────────────────────────────────┐                  │
│  │           OCR Strategy (策略模式)       │                  │
│  │  ┌──────────┐ ┌──────────┐ ┌─────────┐ │                  │
│  │  │ Baidu    │ │ Paddle   │ │ Tencent │ │  ← 可插拔引擎    │
│  │  │ OCR      │ │ OCR      │ │ OCR     │ │                  │
│  │  └────┬─────┘ └──────────┘ └─────────┘ │                  │
│  └───────┼────────────────────────────────┘                  │
│          │                                                    │
│          ▼                                                    │
│  ┌────────────────────────────────────────┐                  │
│  │        BaiduOcrEngine                   │                  │
│  │  ├─ AccessTokenManager  (定时刷新)      │                  │
│  │  ├─ ImagePreprocessor   (压缩/旋转)     │                  │
│  │  ├─ ApiClient           (HTTP调用)      │                  │
│  │  └─ ResultParser        (字段映射)      │                  │
│  └────────────────────────────────────────┘                  │
│                                                               │
│  ┌──────────────┐    ┌──────────────┐                        │
│  │ Result Cache │    │ Retry Queue  │                        │
│  │ (Redis)      │    │ (RabbitMQ)   │                        │
│  └──────────────┘    └──────────────┘                        │
└──────────────────────────────────────────────────────────────┘
```

### 8.3 百度OCR API调用流程

```
报销服务                          OCR服务                     百度OCR API
    │                                │                            │
    │ POST /api/reimbursements       │                            │
    │ (含票据图片)                    │                            │
    │───────────────────────────────►│                            │
    │                                │                            │
    │                                │ 1. 计算图片hash            │
    │                                │ 2. 查Redis缓存             │
    │                                │    ├─ 命中 → 直接返回      │
    │                                │    └─ 未命中 ↓             │
    │                                │                            │
    │                                │ 3. 获取AccessToken         │
    │                                │    (从Redis缓存取，        │
    │                                │     过期前自动刷新)        │
    │                                │                            │
    │                                │ 4. POST /rest/2.0/ocr/    │
    │                                │    v1/vat_invoice          │
    │                                │───────────────────────────►│
    │                                │                            │
    │                                │ 5. 返回结构化识别结果      │
    │                                │◄───────────────────────────│
    │                                │                            │
    │                                │ 6. 解析并缓存结果          │
    │                                │ 7. 存入Redis (hash→结果)   │
    │                                │                            │
    │ 返回识别结果（金额、日期等）    │                            │
    │◄───────────────────────────────│                            │
```

### 8.4 百度OCR核心代码

```java
@Component
public class BaiduOcrEngine implements OcrEngine {

    private final BaiduOcrProperties properties;
    private final RestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;

    @Override
    public OcrResult recognize(byte[] imageBytes) {
        // 1. 去重：相同图片不重复识别
        String imageHash = DigestUtils.md5Hex(imageBytes);
        String cacheKey = "ocr:result:" + imageHash;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return JSON.parseObject(cached, OcrResult.class);
        }

        // 2. 获取AccessToken
        String accessToken = accessTokenManager.getAccessToken();

        // 3. 图片预处理（压缩到4MB以下、自动扶正）
        byte[] processed = imagePreprocessor.process(imageBytes);

        // 4. 调用百度OCR API
        String url = properties.getBaseUrl()
            + "/rest/2.0/ocr/v1/vat_invoice"
            + "?access_token=" + accessToken;

        // 5. Base64编码图片
        String base64Image = Base64.getEncoder().encodeToString(processed);
        String body = "image=" + URLEncoder.encode(base64Image, StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);

        ResponseEntity<BaiduOcrResponse> response = restTemplate.postForEntity(
            url, entity, BaiduOcrResponse.class
        );

        // 6. 解析结果
        OcrResult result = resultParser.parse(response.getBody());

        // 7. 缓存结果（24小时）
        redisTemplate.opsForValue().set(cacheKey,
            JSON.toJSONString(result), 24, TimeUnit.HOURS);

        return result;
    }
}
```

### 8.5 百度OCR返回结果映射

百度增值税发票识别API返回的结构化数据 → 系统内部 `OcrResult`:

```java
@Data
public class OcrResult {
    private String invoiceCode;       // 发票代码
    private String invoiceNumber;     // 发票号码
    private LocalDate invoiceDate;    // 开票日期
    private BigDecimal totalAmount;   // 价税合计（元）
    private BigDecimal taxAmount;     // 税额
    private BigDecimal amountExcludingTax; // 不含税金额
    private String sellerName;        // 销方名称
    private String buyerName;         // 购方名称
    private String invoiceType;       // 发票类型（增值税专票/普票/电子发票）
    private Double confidence;        // 置信度 0-1
    private String rawText;           // 原始识别文本
    private List<InvoiceItem> items;  // 货物明细行
}
```

### 8.6 AccessToken管理

百度OCR AccessToken有效期为30天，需要定时刷新：

```java
@Component
public class AccessTokenManager {

    private final StringRedisTemplate redisTemplate;
    private static final String TOKEN_KEY = "baidu:ocr:access_token";
    private static final long REFRESH_BEFORE_EXPIRE = 24 * 60 * 60; // 提前1天刷新

    @Scheduled(fixedDelay = 3600_000) // 每小时检查
    public void refreshIfNeeded() {
        Long ttl = redisTemplate.getExpire(TOKEN_KEY);
        if (ttl == null || ttl < 0 || ttl < REFRESH_BEFORE_EXPIRE) {
            String newToken = fetchNewToken();
            redisTemplate.opsForValue().set(TOKEN_KEY, newToken,
                29, TimeUnit.DAYS); // 设置29天过期
        }
    }

    public String getAccessToken() {
        String token = redisTemplate.opsForValue().get(TOKEN_KEY);
        if (token == null) {
            token = fetchNewToken();
            redisTemplate.opsForValue().set(TOKEN_KEY, token,
                29, TimeUnit.DAYS);
        }
        return token;
    }

    private String fetchNewToken() {
        // POST https://aip.baidubce.com/oauth/2.0/token
        //   ?grant_type=client_credentials
        //   &client_id={API_KEY}
        //   &client_secret={SECRET_KEY}
    }
}
```

### 8.7 OCR结果缓存Key设计

```
# 按图片内容hash缓存（永久有效，直到LRU淘汰）
ocr:result:{md5hash}  →  {完整OcrResult JSON}

# 按发票号码缓存（同一张发票不重复识别）
ocr:invoice:{invoiceNumber}  →  {md5hash}

# 百度AccessToken缓存
baidu:ocr:access_token  →  {token_string}  过期: 29天

# OCR调用限流计数器
ocr:ratelimit:daily  →  {count}  过期: 当天剩余
```

---

## 9. 安全架构设计

### 9.1 多层安全防护

```
┌──────────────────────────────────────────────────────────────┐
│  第1层: 网络层                                                │
│  ├─ HTTPS 传输加密（生产环境强制）                             │
│  ├─ Nginx 反向代理 + WAF 规则                                 │
│  └─ 内部微服务通信仅在 Docker 内网，不暴露公网端口              │
├──────────────────────────────────────────────────────────────┤
│  第2层: 网关层                                                │
│  ├─ JWT Token 校验（过期、签名、篡改）                         │
│  ├─ 基于 Sentinel 的接口限流                                  │
│  ├─ IP 白名单（内部管理接口如 `/actuator/*`）                  │
│  └─ CORS 跨域控制                                            │
├──────────────────────────────────────────────────────────────┤
│  第3层: 服务层                                                │
│  ├─ RBAC 角色权限控制（@PreAuthorize 方法级）                  │
│  ├─ 数据权限（部门级隔离，只能看本部门数据）                    │
│  ├─ 金额校验（服务端二次校验，防止前端绕过）                    │
│  └─ 操作频率限制（防重复提交、防暴力审批）                      │
├──────────────────────────────────────────────────────────────┤
│  第4层: 数据层                                                │
│  ├─ 密码 BCrypt 哈希存储（即使数据库泄露密码不可逆）            │
│  ├─ 敏感字段加密存储（AES-256）：支付宝账号、银行卡号           │
│  ├─ 逻辑删除（所有业务表 deleted 标记）                        │
│  ├─ 数据库连接 TLS（MySQL 8.0 支持）                          │
│  └─ 数据库审计日志（general_log 或审计插件）                   │
├──────────────────────────────────────────────────────────────┤
│  第5层: 审计层                                                │
│  ├─ 操作审计表 audit_log                                      │
│  ├─ 关键操作不可删除（审批记录、预算变动流水）                  │
│  └─ 数据变更快照（报销单金额修改前后值记录）                    │
└──────────────────────────────────────────────────────────────┘
```

### 9.2 财务敏感数据加密方案

财务数据有更高的合规要求。系统中需要加密存储的字段：

| 字段 | 加密方式 | 原因 |
|-----|---------|------|
| 支付宝账号 | AES-256-CBC，密钥存环境变量 | 个人收款信息 |
| 银行卡号 | AES-256-CBC，密钥存环境变量 | 同上 |
| 报销金额 | 不加密但不可篡改（哈希链校验） | 金额需要参与计算和汇总 |
| 审批意见 | 不加密但禁止修改 | 审计追踪 |

```java
@Component
public class FinancialDataEncryptor {

    private final SecretKey secretKey;

    public FinancialDataEncryptor(@Value("${costlink.encryption.key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    // MyBatis TypeHandler 自动加解密
    public String encrypt(String plainText) {
        if (plainText == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, generateIv());
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new SecurityException("加密失败", e);
        }
    }

    public String decrypt(String cipherText) {
        // 对称解密
    }
}
```

### 9.3 金额防篡改机制

报销金额是财务的核心数据，需要防止在审批和付款环节被篡改：

```java
// 报销单提交时生成金额哈希
public class AmountIntegrityChecker {

    public String computeHash(Reimbursement reimbursement) {
        // 将关键字段拼接后计算HMAC
        String data = reimbursement.getId()
            + "|" + reimbursement.getTotalAmount().toPlainString()
            + "|" + reimbursement.getApplicantId()
            + "|" + reimbursement.getSubmitTime();

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
        return Base64.getEncoder().encodeToString(
            mac.doFinal(data.getBytes(StandardCharsets.UTF_8))
        );
    }

    // 每次审批操作前校验金额完整性
    public boolean verify(Reimbursement reimbursement, String storedHash) {
        return computeHash(reimbursement).equals(storedHash);
    }
}
```

如果报销单金额在校验时发现与提交时不一致，拒绝审批操作并触发安全告警。

### 9.4 审计日志设计

```sql
CREATE TABLE audit_log (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id      VARCHAR(64) NOT NULL,       -- 分布式链路追踪ID
    user_id       BIGINT,                     -- 操作人
    username      VARCHAR(50),
    action        VARCHAR(100) NOT NULL,      -- REIMBURSEMENT_SUBMIT / APPROVAL_APPROVE
    resource_type VARCHAR(50) NOT NULL,       -- REIMBURSEMENT / BUDGET / APPROVAL
    resource_id   VARCHAR(100) NOT NULL,      -- 操作对象ID
    detail        JSON,                       -- 操作详情（变更前后对比）
    ip_address    VARCHAR(45),
    user_agent    VARCHAR(500),
    status        VARCHAR(20),                -- SUCCESS / FAILURE
    error_message VARCHAR(1000),
    create_time   DATETIME NOT NULL,
    INDEX idx_resource (resource_type, resource_id),
    INDEX idx_user_time (user_id, create_time),
    INDEX idx_create_time (create_time)
);
```

```java
// AOP拦截所有Controller方法，自动记录审计日志
@Aspect
@Component
public class AuditLogAspect {

    @Around("@annotation(auditable)")
    public Object logAudit(ProceedingJoinPoint joinPoint, Auditable auditable) {
        AuditLog log = new AuditLog();
        log.setTraceId(MDC.get("traceId"));
        log.setAction(auditable.action());
        log.setResourceType(auditable.resourceType());
        log.setCreateTime(LocalDateTime.now());

        try {
            Object result = joinPoint.proceed();
            log.setStatus("SUCCESS");
            return result;
        } catch (Exception e) {
            log.setStatus("FAILURE");
            log.setErrorMessage(e.getMessage());
            throw e;
        } finally {
            // 异步写入审计日志（用RabbitMQ发送到通知服务持久化）
            rabbitTemplate.convertAndSend("costlink.audit", log);
        }
    }
}
```

### 9.5 JWT安全设计

```
┌─────────────────────────────────────────────────────────────┐
│  JWT Token 设计                                              │
│                                                              │
│  Access Token (短期)           Refresh Token (长期)          │
│  ├─ 有效期: 30分钟             ├─ 有效期: 7天                │
│  ├─ 存储在: 内存/不持久化      ├─ 存储在: Redis (可撤销)     │
│  ├─ 用途: API请求认证          ├─ 用途: 刷新Access Token     │
│  └─ 不可刷新                   └─ 每个用户最多3个同时有效    │
│                                                              │
│  Claims:                                                     │
│  {                                                           │
│    "sub": "user_id",                                         │
│    "role": "DEPARTMENT_HEAD",                                │
│    "departmentId": 10,                                       │
│    "permissions": ["REIMBURSEMENT:SUBMIT",                   │
│                    "REIMBURSEMENT:APPROVE",                  │
│                    "BUDGET:VIEW"],                           │
│    "iat": 1718612345,                                        │
│    "exp": 1718614145,                                        │
│    "jti": "unique-token-id"   ← 用于撤销机制                 │
│  }                                                           │
└─────────────────────────────────────────────────────────────┘
```

**退出登录**时，将 Access Token 的 `jti` 加入 Redis 黑名单（过期时间 = Token自身过期时间），Gateway 校验时额外检查黑名单。

---

## 10. 可观测性设计

### 10.1 三支柱全景

```
┌─────────────────────────────────────────────────────────────┐
│                        可观测性                               │
│                                                              │
│  ┌─────────────┐   ┌─────────────┐   ┌──────────────┐       │
│  │   Logging   │   │   Metrics   │   │   Tracing    │       │
│  │   日志       │   │   指标       │   │   链路追踪   │       │
│  ├─────────────┤   ├─────────────┤   ├──────────────┤       │
│  │ Logback     │   │ Micrometer  │   │ SkyWalking   │       │
│  │ JSON格式    │   │ Prometheus  │   │ Agent 探针   │       │
│  │ ↓           │   │ ↓           │   │ ↓            │       │
│  │ Filebeat    │   │ Prometheus  │   │ SkyWalking   │       │
│  │ ↓           │   │ Server      │   │ OAP Server   │       │
│  │ Elasticsearch│  │ ↓           │   │ ↓            │       │
│  │ ↓           │   │ Grafana     │   │ SkyWalking   │       │
│  │ Kibana      │   │ Dashboard   │   │ UI           │       │
│  └─────────────┘   └─────────────┘   └──────────────┘       │
└─────────────────────────────────────────────────────────────┘
```

### 10.2 结构化日志规范

```json
{
  "timestamp": "2026-07-01T10:30:00.123+08:00",
  "level": "INFO",
  "service": "costlink-reimbursement",
  "instance": "costlink-reimbursement-7d8f9-abc12",
  "traceId": "a1b2c3d4e5f6",
  "spanId": "a1b2c3d4e5f6",
  "userId": "42",
  "action": "REIMBURSEMENT_SUBMIT",
  "reimbursementId": "10086",
  "duration": 345,
  "message": "报销单提交成功",
  "exception": null
}
```

logback-spring.xml 核心配置：

```xml
<appender name="CONSOLE_JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <includeMdcKeyName>traceId</includeMdcKeyName>
        <includeMdcKeyName>spanId</includeMdcKeyName>
        <includeMdcKeyName>userId</includeMdcKeyName>
        <customFields>{"service":"costlink-reimbursement"}</customFields>
    </encoder>
</appender>
```

### 10.3 关键业务指标

| 指标名 | 类型 | 说明 | 告警阈值 |
|-------|------|------|---------|
| `reimbursement_submit_total` | Counter | 报销提交总量 | — |
| `reimbursement_submit_duration_seconds` | Histogram | 提交耗时 | P99 > 3s 告警 |
| `budget_freeze_duration_seconds` | Histogram | 预算冻结耗时 | P99 > 1s 告警 |
| `ocr_recognize_duration_seconds` | Histogram | OCR识别耗时 | P99 > 5s 告警 |
| `ocr_recognize_failures_total` | Counter | OCR失败次数 | 5分钟内 > 10 告警 |
| `approval_pending_total` | Gauge | 待审批数量 | 部门级监控 |
| `budget_usage_rate` | Gauge | 预算使用率 | > 80% 告警 |
| `feign_requests_failures_total` | Counter | Feign调用失败 | 5分钟内 > 5 告警 |

### 10.4 SkyWalking 链路示例

一次完整的报销提交流程在 SkyWalking 中的链路：

```
Trace: a1b2c3d4e5f6 (总耗时: 1.2s)
│
├─ [costlink-gateway]        JWT校验              35ms
│  └─ [costlink-reimbursement] POST /api/reimbursements/{id}/submit
│     │
│     ├─ [costlink-reimbursement] 数据库写入      45ms
│     │
│     ├─ [costlink-budget]       POST /internal/budgets/freeze
│     │  ├─ Redis 分布式锁获取                   12ms
│     │  ├─ MySQL 余额查询更新                    28ms
│     │  └─ 流水记录写入                          15ms
│     │  (总耗时: 68ms)
│     │
│     ├─ [costlink-approval]     POST /internal/approvals/start
│     │  ├─ 审批模板查询                          22ms
│     │  ├─ 审批链生成                             8ms
│     │  └─ 实例+节点写入                         18ms
│     │  (总耗时: 52ms)
│     │
│     └─ [costlink-reimbursement] 状态更新+事件发送    40ms
│
└─ [RabbitMQ] 异步链路
   ├─ [costlink-ocr]            票据识别           850ms
   └─ [costlink-notification]   发送通知           120ms
```

---

## 11. 部署架构

### 11.1 Docker Compose 开发环境

```yaml
version: '3.8'

services:
  # ========== 基础设施 ==========
  nacos:
    image: nacos/nacos-server:v2.3.1
    container_name: costlink-nacos
    ports: ["8848:8848", "9848:9848"]
    environment:
      MODE: standalone
      SPRING_DATASOURCE_PLATFORM: mysql
      MYSQL_SERVICE_HOST: mysql
      MYSQL_SERVICE_DB_NAME: nacos_config
      MYSQL_SERVICE_USER: root
      MYSQL_SERVICE_PASSWORD: ${DB_ROOT_PASSWORD}

  mysql:
    image: mysql:8.0
    container_name: costlink-mysql
    ports: ["3306:3306"]
    environment:
      MYSQL_ROOT_PASSWORD: ${DB_ROOT_PASSWORD}
    volumes:
      - mysql-data:/var/lib/mysql
      - ./init:/docker-entrypoint-initdb.d

  redis:
    image: redis:7-alpine
    container_name: costlink-redis
    ports: ["6379:6379"]
    command: redis-server --requirepass ${REDIS_PASSWORD}
    volumes: [redis-data:/data]

  rabbitmq:
    image: rabbitmq:3.12-management-alpine
    container_name: costlink-rabbitmq
    ports: ["5672:5672", "15672:15672"]
    environment:
      RABBITMQ_DEFAULT_USER: costlink
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD}

  elasticsearch:
    image: elasticsearch:8.11.0
    container_name: costlink-es
    ports: ["9200:9200"]
    environment:
      discovery.type: single-node
      xpack.security.enabled: "false"
    volumes: [es-data:/usr/share/elasticsearch/data]

  kibana:
    image: kibana:8.11.0
    container_name: costlink-kibana
    ports: ["5601:5601"]
    environment:
      ELASTICSEARCH_HOSTS: http://elasticsearch:9200
    depends_on: [elasticsearch]

  # ========== 微服务 ==========
  costlink-gateway:
    build: ./costlink-gateway
    container_name: costlink-gateway
    ports: ["8080:8080"]
    environment:
      NACOS_SERVER_ADDR: nacos:8848
      REDIS_HOST: redis
      REDIS_PASSWORD: ${REDIS_PASSWORD}
    depends_on: [nacos, redis]

  costlink-auth:
    build: ./costlink-auth
    container_name: costlink-auth
    ports: ["8084:8084"]
    environment:
      NACOS_SERVER_ADDR: nacos:8848
      DB_URL: jdbc:mysql://mysql:3306/costlink_shared?useSSL=false
      DB_USER: root
      DB_PASSWORD: ${DB_ROOT_PASSWORD}
      REDIS_HOST: redis
      REDIS_PASSWORD: ${REDIS_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
    depends_on: [nacos, mysql, redis]

  costlink-reimbursement:
    build: ./costlink-reimbursement
    ports: ["8081:8081"]
    environment:
      NACOS_SERVER_ADDR: nacos:8848
      DB_URL: jdbc:mysql://mysql:3306/costlink_reimbursement?useSSL=false
      DB_USER: root
      DB_PASSWORD: ${DB_ROOT_PASSWORD}
      REDIS_HOST: redis
      REDIS_PASSWORD: ${REDIS_PASSWORD}
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_USERNAME: costlink
      RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD}
    depends_on: [nacos, mysql, redis, rabbitmq]
    deploy:
      replicas: 2  # 报销服务多实例

  costlink-budget:
    build: ./costlink-budget
    ports: ["8082:8082"]
    environment:
      NACOS_SERVER_ADDR: nacos:8848
      DB_URL: jdbc:mysql://mysql:3306/costlink_budget?useSSL=false
      DB_USER: root
      DB_PASSWORD: ${DB_ROOT_PASSWORD}
      REDIS_HOST: redis
      REDIS_PASSWORD: ${REDIS_PASSWORD}
    depends_on: [nacos, mysql, redis]

  costlink-approval:
    build: ./costlink-approval
    ports: ["8083:8083"]
    environment:
      NACOS_SERVER_ADDR: nacos:8848
      DB_URL: jdbc:mysql://mysql:3306/costlink_approval?useSSL=false
      DB_USER: root
      DB_PASSWORD: ${DB_ROOT_PASSWORD}
      REDIS_HOST: redis
      REDIS_PASSWORD: ${REDIS_PASSWORD}
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_USERNAME: costlink
      RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD}
    depends_on: [nacos, mysql, redis, rabbitmq]
    deploy:
      replicas: 2

  costlink-ocr:
    build: ./costlink-ocr
    ports: ["8085:8085"]
    environment:
      NACOS_SERVER_ADDR: nacos:8848
      REDIS_HOST: redis
      REDIS_PASSWORD: ${REDIS_PASSWORD}
      BAIDU_OCR_APP_ID: ${BAIDU_OCR_APP_ID}
      BAIDU_OCR_API_KEY: ${BAIDU_OCR_API_KEY}
      BAIDU_OCR_SECRET_KEY: ${BAIDU_OCR_SECRET_KEY}
    depends_on: [nacos, redis]
    deploy:
      replicas: 2

  costlink-notification:
    build: ./costlink-notification
    ports: ["8086:8086"]
    environment:
      NACOS_SERVER_ADDR: nacos:8848
      DB_URL: jdbc:mysql://mysql:3306/costlink_notification?useSSL=false
      DB_USER: root
      DB_PASSWORD: ${DB_ROOT_PASSWORD}
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_USERNAME: costlink
      RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD}
    depends_on: [nacos, mysql, rabbitmq]

  costlink-report:
    build: ./costlink-report
    ports: ["8087:8087"]
    environment:
      NACOS_SERVER_ADDR: nacos:8848
      # 连接各库只读副本
      DB_REIMBURSEMENT_URL: jdbc:mysql://mysql:3306/costlink_reimbursement?useSSL=false
      DB_BUDGET_URL: jdbc:mysql://mysql:3306/costlink_budget?useSSL=false
      DB_USER: readonly
      DB_PASSWORD: ${READONLY_DB_PASSWORD}
    depends_on: [nacos, mysql]

  # ========== 前端 ==========
  nginx:
    image: nginx:1.24-alpine
    container_name: costlink-nginx
    ports: ["80:80", "443:443"]
    volumes:
      - ./costlink-frontend/dist:/usr/share/nginx/html
      - ./nginx.conf:/etc/nginx/conf.d/default.conf
      - ./ssl:/etc/nginx/ssl
    depends_on: [costlink-gateway]

  # ========== 可观测性 ==========
  skywalking-oap:
    image: apache/skywalking-oap-server:9.7.0
    container_name: costlink-sw-oap
    ports: ["11800:11800", "12800:12800"]
    environment:
      SW_STORAGE: elasticsearch
      SW_STORAGE_ES_CLUSTER_NODES: elasticsearch:9200
    depends_on: [elasticsearch]

  skywalking-ui:
    image: apache/skywalking-ui:9.7.0
    container_name: costlink-sw-ui
    ports: ["8088:8080"]
    environment:
      SW_OAP_ADDRESS: http://skywalking-oap:12800
    depends_on: [skywalking-oap]

  prometheus:
    image: prom/prometheus:v2.48.0
    container_name: costlink-prometheus
    ports: ["9090:9090"]
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml

  grafana:
    image: grafana/grafana:10.2.0
    container_name: costlink-grafana
    ports: ["3000:3000"]
    environment:
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_PASSWORD}
    volumes: [grafana-data:/var/lib/grafana]

volumes:
  mysql-data:
  redis-data:
  es-data:
  grafana-data:
```

### 11.2 数据库初始化脚本

```sql
-- init/01-create-databases.sql
CREATE DATABASE IF NOT EXISTS costlink_shared
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS costlink_reimbursement
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS costlink_budget
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS costlink_approval
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS costlink_notification
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 创建只读用户（报表服务使用）
CREATE USER 'readonly'@'%' IDENTIFIED BY 'readonly_password';
GRANT SELECT ON costlink_reimbursement.* TO 'readonly'@'%';
GRANT SELECT ON costlink_budget.* TO 'readonly'@'%';
GRANT SELECT ON costlink_approval.* TO 'readonly'@'%';
```

### 11.3 生产环境迁移路径

当前 Docker Compose 适合开发和小规模部署。当需要上生产时：

| 组件 | Docker Compose（当前） | K8s 生产（目标） |
|-----|----------------------|-----------------|
| 服务编排 | docker-compose.yml | Helm Charts |
| 服务发现 | Nacos Standalone | Nacos Cluster（3节点） |
| 数据库 | MySQL 单实例 | MySQL 主从 + 读写分离 |
| 配置管理 | Nacos Config | Nacos Config + 外部Secrets |
| 日志 | Filebeat → ES | DaemonSet Filebeat → ES Cluster |
| SSL | Nginx 终止 | Ingress Controller + cert-manager |
| CI/CD | 手动构建 | Jenkins / GitHub Actions + ArgoCD |

---

## 12. 关键业务流程设计

### 12.1 报销提交完整时序

```
申请人       Gateway      报销服务      预算服务      审批服务      OCR服务     通知服务
  │              │            │            │            │            │           │
  │ POST /submit │            │            │            │            │           │
  │─────────────►│            │            │            │            │           │
  │              │ JWT校验    │            │            │            │           │
  │              │───────────►│            │            │            │           │
  │              │            │            │            │            │           │
  │              │            │ 1.创建报销单│            │            │           │
  │              │            │ (DRAFT)    │            │            │           │
  │              │            │            │            │            │           │
  │              │            │ 2.冻结预算  │            │            │           │
  │              │            │───────────►│            │            │           │
  │              │            │            │ 查余额     │            │           │
  │              │            │            │ 加锁       │            │           │
  │              │            │            │ 更新冻结额 │            │           │
  │              │            │◄───────────│            │            │           │
  │              │            │            │            │            │           │
  │              │            │ 3.启动审批  │            │            │           │
  │              │            │────────────────────────►│            │           │
  │              │            │            │            │ 生成审批链 │           │
  │              │            │            │            │ 创建实例   │           │
  │              │            │◄────────────────────────│            │           │
  │              │            │            │            │            │           │
  │              │            │ 4.更新状态  │            │            │           │
  │              │            │ PENDING     │            │            │           │
  │              │            │            │            │            │           │
  │              │            │ 5.异步OCR   │            │            │           │
  │              │            │─────────────────────────────────────►│           │
  │              │            │            │            │            │           │
  │              │            │ 6.通知下一审批人                      │           │
  │              │            │─────────────────────────────────────────────────►│
  │              │            │            │            │            │           │
  │  返回成功    │            │            │            │            │           │
  │◄─────────────│◄───────────│            │            │            │           │
  │              │            │            │            │            │           │
  │              │            │ (异步)     │            │            │           │
  │              │            │ OCR结果回写│            │◄───────────│           │
  │              │            │ 更新票据金额            │            │           │
```

### 12.2 审批通过后的预算扣减（事件驱动）

```
审批服务                   RabbitMQ                预算服务              报销服务
  │                            │                      │                    │
  │ 最终节点审批通过            │                      │                    │
  │                            │                      │                    │
  │ 发送ApprovalCompleted      │                      │                    │
  │───────────────────────────►│                      │                    │
  │                            │                      │                    │
  │                            │ ── 消费事件 ────────►│                    │
  │                            │                      │ 1.冻结→已用       │
  │                            │                      │ 2.记录流水        │
  │                            │                      │                    │
  │                            │ ── 消费事件 ────────────────────────────►│
  │                            │                      │  更新报销单状态    │
  │                            │                      │  DRAFT→APPROVED   │
  │                            │                      │                    │
  │                            │ ── 消费事件 ────────────────────────────►│
  │                            │                      │  通知申请人        │
  │                            │                      │  "报销已通过"      │
```

### 12.3 预算超支控制流程

```
报销服务提交报销
    │
    ▼
调用预算服务 /internal/budgets/check
    │
    ▼
预算服务检查
    │
    ├─ 可用余额 >= 报销金额 → 返回 ALLOW
    │
    ├─ 可用余额 < 报销金额 且 金额 > 0 → 检查控制策略
    │     │
    │     ├─ 硬控制（strict）→ 返回 DENY，"预算不足，可用: xxx"
    │     │    └─ 报销服务: 拒绝提交，返回错误给用户
    │     │
    │     ├─ 软控制（soft）→ 返回 WARNING，"预算不足，已触发预警"
    │     │    └─ 报销服务: 允许提交，但标记为"超预算审批"
    │     │                  审批链中额外增加财务总监审批节点
    │     │
    │     └─ 弹性控制（flexible）→ 检查同部门其他科目余额
    │           │
    │           ├─ 有其他科目余额可挪用 → 返回 ALLOW，挪用到当前科目
    │           └─ 无余额可挪用 → 同硬控制
    │
    └─ 可用余额 < 预警阈值（如 < 20%）→ 返回 ALLOW + WARNING标记
           └─ 通知服务: 发送预算预警给部门主管和财务
```

---

## 13. 项目结构与模块规划

### 13.1 多模块 Maven 结构

```
costlink-microservice/
│
├── pom.xml                              # 父POM，依赖管理
│
├── costlink-common/                     # 公共模块（所有服务依赖）
│   ├── pom.xml
│   └── src/main/java/com/costlink/common/
│       ├── dto/                         # 共享DTO
│       │   ├── Result.java              # 统一响应封装
│       │   ├── PageResult.java          # 分页响应
│       │   └── UserContext.java         # 从Header提取的用户信息
│       ├── exception/                   # 全局异常
│       │   ├── BusinessException.java
│       │   ├── ErrorCode.java
│       │   └── GlobalExceptionHandler.java
│       ├── feign/                       # Feign客户端接口定义
│       │   ├── BudgetClient.java
│       │   ├── ApprovalClient.java
│       │   ├── OcrClient.java
│       │   └── AuthClient.java
│       ├── mq/                          # 消息定义
│       │   ├── ExchangeConstants.java
│       │   ├── QueueConstants.java
│       │   └── event/                   # 事件对象
│       │       ├── ReimbursementSubmittedEvent.java
│       │       ├── ApprovalCompletedEvent.java
│       │       └── BudgetExceededEvent.java
│       └── util/                        # 工具类
│           ├── JwtUtil.java
│           └── FinancialDataEncryptor.java
│
├── costlink-gateway/                    # API网关
│   ├── pom.xml
│   └── src/main/java/com/costlink/gateway/
│       ├── GatewayApplication.java
│       ├── filter/
│       │   ├── JwtAuthenticationFilter.java
│       │   └── RateLimitFilter.java
│       └── config/
│           └── RouteConfig.java
│
├── costlink-auth/                       # 认证服务
│   ├── pom.xml
│   └── src/main/java/com/costlink/auth/
│       ├── AuthApplication.java
│       ├── controller/
│       │   ├── AuthController.java      # 对外: /api/auth/**
│       │   └── InternalUserController.java  # 内部: /internal/users/**
│       ├── service/
│       │   ├── AuthService.java
│       │   └── UserService.java
│       ├── mapper/
│       │   └── UserMapper.java
│       ├── entity/
│       │   ├── User.java
│       │   └── Role.java
│       └── config/
│           └── SecurityConfig.java
│
├── costlink-reimbursement/              # 报销服务 ★ 核心
│   ├── pom.xml
│   └── src/main/java/com/costlink/reimbursement/
│       ├── ReimbursementApplication.java
│       ├── controller/
│       │   └── ReimbursementController.java
│       ├── service/
│       │   ├── ReimbursementService.java
│       │   ├── ReimbursementSubmitSaga.java    # Saga编排
│       │   └── ExpenseItemService.java
│       ├── mapper/
│       │   ├── ReimbursementMapper.java
│       │   ├── ExpenseItemMapper.java
│       │   └── OutboxMessageMapper.java
│       ├── entity/
│       │   ├── Reimbursement.java
│       │   ├── ExpenseItem.java
│       │   ├── Attachment.java
│       │   └── OutboxMessage.java
│       ├── mq/
│       │   ├── ReimbursementEventPublisher.java
│       │   └── ApprovalResultConsumer.java
│       └── config/
│           └── SentinelConfig.java
│
├── costlink-budget/                     # 预算服务 ★ 核心
│   ├── pom.xml
│   └── src/main/java/com/costlink/budget/
│       ├── BudgetApplication.java
│       ├── controller/
│       │   ├── BudgetController.java        # 对外: /api/budgets/**
│       │   └── BudgetInternalController.java # 内部: /internal/budgets/**
│       ├── service/
│       │   ├── BudgetService.java
│       │   └── BudgetFreezeService.java     # 冻结/解冻/消费
│       ├── mapper/
│       │   ├── BudgetMapper.java
│       │   ├── BudgetLineMapper.java
│       │   └── BudgetChangeLogMapper.java
│       ├── entity/
│       │   ├── Budget.java
│       │   ├── BudgetLine.java
│       │   └── BudgetChangeLog.java
│       └── config/
│           └── RedissonConfig.java          # 分布式锁配置
│
├── costlink-approval/                   # 审批服务 ★ 核心
│   ├── pom.xml
│   └── src/main/java/com/costlink/approval/
│       ├── ApprovalApplication.java
│       ├── controller/
│       │   ├── ApprovalController.java
│       │   └── ApprovalInternalController.java
│       ├── service/
│       │   ├── ApprovalService.java
│       │   └── ApprovalChainEngine.java     # 审批链引擎
│       ├── mapper/
│       │   ├── ApprovalTemplateMapper.java
│       │   ├── ApprovalInstanceMapper.java
│       │   └── ApprovalRecordMapper.java
│       └── entity/
│           ├── ApprovalTemplate.java
│           ├── ApprovalInstance.java
│           ├── ApprovalNode.java
│           └── ApprovalRecord.java
│
├── costlink-ocr/                        # OCR服务
│   ├── pom.xml
│   └── src/main/java/com/costlink/ocr/
│       ├── OcrApplication.java
│       ├── controller/
│       │   └── OcrController.java
│       ├── engine/
│       │   ├── OcrEngine.java               # 接口
│       │   ├── BaiduOcrEngine.java          # 百度OCR实现
│       │   └── OcrEngineFactory.java        # 策略工厂
│       ├── service/
│       │   └── OcrService.java
│       ├── config/
│       │   ├── BaiduOcrProperties.java
│       │   └── AccessTokenManager.java
│       └── dto/
│           ├── OcrResult.java
│           └── BaiduOcrResponse.java
│
├── costlink-notification/               # 通知服务
│   ├── pom.xml
│   └── src/main/java/com/costlink/notification/
│       ├── NotificationApplication.java
│       ├── mq/
│       │   └── NotificationEventConsumer.java
│       ├── channel/
│       │   ├── EmailChannel.java
│       │   ├── WechatWorkChannel.java
│       │   └── InAppChannel.java
│       └── service/
│           └── NotificationService.java
│
├── costlink-report/                     # 报表服务
│   ├── pom.xml
│   └── src/main/java/com/costlink/report/
│       ├── ReportApplication.java
│       ├── controller/
│       │   └── ReportController.java
│       └── service/
│           ├── ReimbursementReportService.java
│           └── BudgetExecutionReportService.java
│
├── costlink-frontend/                   # 前端（Vue 3）
│   ├── package.json
│   └── src/
│       ├── api/                         # API请求
│       │   ├── reimbursement.js
│       │   ├── budget.js
│       │   ├── approval.js
│       │   └── auth.js
│       ├── views/                       # 页面
│       │   ├── reimbursement/
│       │   ├── budget/
│       │   ├── approval/
│       │   └── report/
│       ├── stores/                      # Pinia
│       └── router/
│
├── docker-compose.yml                   # 开发环境编排
├── init/
│   └── init.sql                         # 数据库初始化
├── .env.example                         # 环境变量模板
└── README.md
```

### 13.2 父POM依赖管理核心配置

```xml
<!-- 父 pom.xml -->
<properties>
    <java.version>17</java.version>
    <spring-boot.version>3.2.7</spring-boot.version>
    <spring-cloud.version>2023.0.3</spring-cloud.version>
    <spring-cloud-alibaba.version>2023.0.1.0</spring-cloud-alibaba.version>
    <mybatis-plus.version>3.5.7</mybatis-plus.version>
    <redisson.version>3.32.0</redisson.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>${spring-boot.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-dependencies</artifactId>
            <version>${spring-cloud.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-alibaba-dependencies</artifactId>
            <version>${spring-cloud-alibaba.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 13.3 单个微服务 POM 示例

```xml
<!-- costlink-reimbursement/pom.xml -->
<dependencies>
    <!-- 公共模块 -->
    <dependency>
        <groupId>com.costlink</groupId>
        <artifactId>costlink-common</artifactId>
        <version>${project.version}</version>
    </dependency>

    <!-- Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Nacos 服务注册 -->
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
    </dependency>

    <!-- Nacos 配置中心 -->
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
    </dependency>

    <!-- OpenFeign -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-openfeign</artifactId>
    </dependency>

    <!-- Spring Cloud LoadBalancer -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-loadbalancer</artifactId>
    </dependency>

    <!-- Sentinel 熔断降级 -->
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
    </dependency>

    <!-- MyBatis-Plus -->
    <dependency>
        <groupId>com.baomidou</groupId>
        <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    </dependency>

    <!-- MySQL -->
    <dependency>
        <groupId>com.mysql</groupId>
        <artifactId>mysql-connector-j</artifactId>
    </dependency>

    <!-- RabbitMQ -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-amqp</artifactId>
    </dependency>

    <!-- Redis + Redisson -->
    <dependency>
        <groupId>org.redisson</groupId>
        <artifactId>redisson-spring-boot-starter</artifactId>
    </dependency>

    <!-- Actuator + Prometheus -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>
</dependencies>
```

---

## 附录 A: 与单体架构的差异对照

| 维度 | 原单体架构 (v1.0) | 微服务架构 (v2.0) |
|-----|-----------------|-----------------|
| 代码组织 | 单一 Spring Boot 项目，按包分层 | 8个独立模块，按业务域拆分 |
| 数据库 | 单一 costlink 数据库 | 每核心服务独立Schema |
| 报销+预算事务 | 本地 @Transactional | Saga编排 + 最终一致性 |
| 审批引擎 | 硬编码审批链（ADMIN_USERS） | 独立审批服务，灵活审批模板 |
| OCR | Tesseract（自部署） | 百度OCR API + 引擎可插拔 |
| 部署单元 | 1个Jar包 + 1个前端dist | 8个服务容器，独立部署和伸缩 |
| 服务发现 | 无（Nginx直连） | Nacos 注册中心 |
| 配置管理 | application.yml 文件 | Nacos 统一配置中心 |
| 通信方式 | 方法调用 | Feign同步 + RabbitMQ异步 |
| 容错 | 无 | Sentinel 熔断降级 |
| 监控 | 无 | ELK + SkyWalking + Prometheus |
| 前端 | 直连后端:8080 | 通过Gateway:8080统一访问 |

## 附录 B: 关键技术选型理由

| 选择 | 备选 | 选择理由 |
|-----|------|---------|
| **Nacos** | Eureka + Config | 服务发现+配置管理二合一，阿里开源生态成熟，中文社区友好。Eureka 2.0 已停维 |
| **RabbitMQ** | Kafka | RabbitMQ 消息可靠性更高（手动ACK+死信），适合财务场景。Kafka 更适合海量日志/流处理 |
| **Sentinel** | Hystrix | Hystrix 已停维。Sentinel 与 Spring Cloud Alibaba 深度集成，支持控制台动态改规则 |
| **百度OCR** | Tesseract / PaddleOCR | 增值税发票识别准确率最高，零部署成本，免费额度满足中小规模 |
| **Redisson** | Jedis / Lettuce | 内置分布式锁、信号量，API 与 Java 并发包对齐，学习成本低 |
| **SkyWalking** | Jaeger / Zipkin | 国产开源、中文文档好、Agent 探针零代码侵入、与 Spring Cloud 集成简单 |

## 附录 C: 参考资源

- Spring Cloud Alibaba: https://github.com/alibaba/spring-cloud-alibaba
- Nacos 官方文档: https://nacos.io/docs/latest/
- Sentinel 官方文档: https://sentinelguard.io/zh-cn/docs/
- RabbitMQ 官方文档: https://www.rabbitmq.com/docs
- 百度OCR - 增值税发票识别: https://cloud.baidu.com/doc/OCR/s/8k3h8xtzs
- SkyWalking 官方文档: https://skywalking.apache.org/docs/
- Redisson 分布式锁: https://github.com/redisson/redisson/wiki
- Saga 模式参考: https://microservices.io/patterns/data/saga.html

---

> **版本记录**: v2.0-Microservice — 2026-07-01。基于原 CostLink v1.0 单体设计框架，采用核心业务拆分策略，引入微服务基础设施（Nacos、RabbitMQ、Sentinel、SkyWalking），以百度OCR替代Tesseract，增加财务数据安全防护层。后续各服务的详细设计文档将基于此框架展开。
