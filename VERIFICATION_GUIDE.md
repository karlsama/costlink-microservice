# CostLink 完整验收方案

> 每条 curl 都能直接跑。按顺序执行，不做一条不跳到下一条。
> 所有服务通过 Gateway (:8080) 统一入口。

---

## 前置条件

```cmd
REM 1. MySQL 管理员 CMD（不是 PowerShell）
cd "C:\Program Files\MySQL\MySQL Server 8.4\bin"
.\mysqld --defaults-file="C:\ProgramData\MySQL\my.ini" --console
```

```bash
# 2. WSL 终端 — Docker 中间件
cd /mnt/f/project_007
docker compose up nacos redis rabbitmq -d

# 验证
docker compose logs nacos | grep "started successfully" | tail -1
```

```cmd
REM 3. CMD 终端 — 按需启动后端服务（测哪个起哪个，不用全开）

REM ---- 认证服务 (8084) ----
cd /d F:\project_007\costlink-auth
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"

REM ---- Gateway (8080) ----
cd /d F:\project_007\costlink-gateway
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"

REM ---- 报销服务 Mock (8081) ----
cd /d F:\project_007\costlink-reimbursement
mvn spring-boot:run "-Dspring-boot.run.profiles=dev,mock"

REM ---- 预算服务 (8082) ----
cd /d F:\project_007\costlink-budget
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"

REM ---- 审批服务 Mock (8083) ----
cd /d F:\project_007\costlink-approval
mvn spring-boot:run "-Dspring-boot.run.profiles=dev,mock"

REM ---- OCR 服务 (8085) —— 先设凭据 ----
set BAIDU_OCR_API_KEY=7rkfAoOJffUKw2aLmjgIYFJo
set BAIDU_OCR_SECRET_KEY=QRt53HFQSrkajHDsWjDE8YWw8ffTILon
set BAIDU_OCR_APP_ID=123874271
cd /d F:\project_007\costlink-ocr
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"

REM ---- 通知服务 (8086) ----
cd /d F:\project_007\costlink-notification
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"

REM ---- 报表服务 (8087) ----
cd /d F:\project_007\costlink-report
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

**每个服务开一个独立的 CMD 窗口。** 不用全开——测报销时只开 Auth + Gateway + Reimbursement 就行。测预算只开 Budget 就行（直连 :8082）。

```bash
# 4. 确认所有启动的服务已注册到 Nacos
curl -s http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20 | python3 -c "import sys,json;[print(s['serviceName']) for s in json.load(sys.stdin)['doms']]" 2>/dev/null

# 或者直接打开 Nacos 控制台 → 服务管理 → 服务列表
# http://127.0.0.1:8848/nacos
```

---

## 一、认证服务

### 1.1 正常登录
```bash
curl -X POST http://127.0.0.1:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```
**期望**: 200, accessToken 和 refreshToken 都非空, userInfo.role=ADMIN

### 1.2 错误密码
```bash
curl -X POST http://127.0.0.1:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"wrong"}'
```
**期望**: code=10501

### 1.3 刷新 Token
```bash
# 先用 1.1 的 refreshToken 替换 {REFRESH_TOKEN}
curl -X POST http://127.0.0.1:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"{REFRESH_TOKEN}"}'
```
**期望**: 200, 新的 accessToken

---

## 二、报销服务（Mock 模式）

```bash
# 获取 Token，后面全用它
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
echo $TOKEN
```

### 2.1 创建报销单（草稿）
```bash
curl -X POST http://127.0.0.1:8080/api/reimbursements \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"测试报销","expenseType":"TRAVEL","items":[{"category":"TRAVEL_TRANSPORT","amount":500.00,"receiptDate":"2026-07-01"}]}'
```
**期望**: 200, status=DRAFT, 返回 reimbursementId

### 2.2 查询详情
```bash
curl http://127.0.0.1:8080/api/reimbursements/{id} -H "Authorization: Bearer $TOKEN"
```
**期望**: 200, 返回完整字段

### 2.3 分页查询
```bash
curl "http://127.0.0.1:8080/api/reimbursements?page=1&size=10" -H "Authorization: Bearer $TOKEN"
```
**期望**: 200, records 数组

### 2.4 提交报销单（Mock Saga）
```bash
curl -X POST http://127.0.0.1:8080/api/reimbursements/{id}/submit \
  -H "Authorization: Bearer $TOKEN"
```
**期望**: 200, status=PENDING

### 2.5 带 Token 访问——通过 Gateway 鉴权
```bash
# 不带 Token 应返回 401
curl http://127.0.0.1:8080/api/reimbursements
```
**期望**: 401

---

## 三、预算服务

### 3.1 准备测试数据
```sql
-- 确保 budget 和 budget_line 有数据
INSERT INTO costlink_budget.budget (id, fiscal_year, department_id, total_amount, status) VALUES
(1, 2026, 10, 100000.00, 'ACTIVE');

INSERT INTO costlink_budget.budget_line (id, budget_id, category, total_amount, control_strategy) VALUES
(10, 1, 'TRAVEL_TRANSPORT', 50000.00, 'STRICT'),
(11, 1, 'OFFICE', 30000.00, 'SOFT');
```

### 3.2 冻结预算
```bash
curl -X POST http://127.0.0.1:8082/internal/budgets/freeze \
  -H "Content-Type: application/json" \
  -d '{"reimbursementId":1,"departmentId":10,"items":[{"category":"TRAVEL_TRANSPORT","amount":1000.00}]}'
```
**期望**: 200, success=true, availableAfterFreeze=49000

### 3.3 超额冻结被拒
```bash
curl -X POST http://127.0.0.1:8082/internal/budgets/freeze \
  -H "Content-Type: application/json" \
  -d '{"reimbursementId":2,"departmentId":10,"items":[{"category":"TRAVEL_TRANSPORT","amount":999999.00}]}'
```
**期望**: 200, success=false, "预算不足"

### 3.4 消费冻结金额
```bash
curl -X POST http://127.0.0.1:8082/internal/budgets/consume \
  -H "Content-Type: application/json" \
  -d '{"reimbursementId":1,"items":[{"category":"TRAVEL_TRANSPORT","amount":1000.00}]}'
```
**期望**: 200

### 3.5 解冻
```bash
# 先冻结一笔新的
curl -X POST http://127.0.0.1:8082/internal/budgets/freeze \
  -H "Content-Type: application/json" \
  -d '{"reimbursementId":3,"departmentId":10,"items":[{"category":"TRAVEL_TRANSPORT","amount":500.00}]}'

# 再解冻
curl -X POST http://127.0.0.1:8082/internal/budgets/unfreeze \
  -H "Content-Type: application/json" \
  -d '{"reimbursementId":3}'
```
**期望**: 200

### 3.6 查询可用余额
```bash
curl "http://127.0.0.1:8082/internal/budgets/available?departmentId=10&category=TRAVEL_TRANSPORT"
```
**期望**: 200, availableAmount 正确

---

## 四、审批服务（Mock 模式）

### 4.1 启动审批链
```bash
# 金额 500 → 命中 priority=1 → 1 个节点
curl -X POST http://127.0.0.1:8083/internal/approvals/start \
  -H "Content-Type: application/json" \
  -d '{"reimbursementId":1,"applicantId":2,"departmentId":10,"totalAmount":500.00,"expenseType":"TRAVEL"}'

# 金额 3000 → 命中 priority=2 → 2 个节点
curl -X POST http://127.0.0.1:8083/internal/approvals/start \
  -H "Content-Type: application/json" \
  -d '{"reimbursementId":2,"applicantId":2,"departmentId":10,"totalAmount":3000.00,"expenseType":"TRAVEL"}'
```
**期望**: 两次都 200, nodeChain 长度分别为 1 和 2

### 4.2 审批通过
```bash
curl -X POST http://127.0.0.1:8083/api/approvals/{instanceId}/approve \
  -H "Content-Type: application/json" \
  -d '{"action":"APPROVE","comment":"ok"}'
```
**期望**: 200

### 4.3 审批驳回
```bash
# 先启动一个新实例
curl -X POST http://127.0.0.1:8083/api/approvals/{instanceId}/reject \
  -H "Content-Type: application/json" \
  -d '{"action":"REJECT","comment":"no"}'
```
**期望**: 200, 实例状态 REJECTED

### 4.4 我的待办
```bash
curl "http://127.0.0.1:8083/api/approvals/pending?approverId=1"
```
**期望**: 200, 列表

---

## 五、OCR 服务

### 5.1 同步识别
```bash
# 找一张发票 JPG，Base64 编码后发
BASE64=$(base64 -w 0 /path/to/invoice.jpg)
FILEHASH=$(md5sum /path/to/invoice.jpg | cut -d' ' -f1)

curl -X POST http://127.0.0.1:8085/internal/ocr/recognize \
  -H "Content-Type: application/json" \
  -d "{\"attachmentId\":1,\"reimbursementId\":100,\"base64Image\":\"$BASE64\",\"fileHash\":\"$FILEHASH\"}"
```
**期望**: 200, totalAmount 非空, invoiceNumber 非空

### 5.2 异步识别
```bash
curl -X POST http://127.0.0.1:8085/internal/ocr/recognize-async \
  -H "Content-Type: application/json" \
  -d '{"attachmentId":1,"reimbursementId":100,"base64Image":"...","fileHash":"abc"}'
```
**期望**: 200

### 5.3 缓存命中
再调一次 5.1——同样图片不应再调百度，直接返回缓存。日志中无百度 HTTP 请求。

---

## 六、通知服务

### 6.1 手动发 MQ 验证
RabbitMQ 管理界面 http://127.0.0.1:15672 → Queues → q.approval.node.completed → Publish message：
```json
{"reimbursementId":100,"title":"测试","amount":500.00,"nextApproverId":1,"nextApproverName":"管理员"}
```

### 6.2 查数据库验证
```sql
SELECT * FROM costlink_notification.message ORDER BY create_time DESC LIMIT 5;
```
**期望**: user_id=1, message_type=APPROVAL_NOTIFY

---

## 七、报表服务

```bash
# 确认有测试数据后执行
curl "http://127.0.0.1:8087/api/reports/reimbursement-summary?deptId=10&year=2026"
curl "http://127.0.0.1:8087/api/reports/budget-execution?deptId=10&year=2026"
curl "http://127.0.0.1:8087/api/reports/department-ranking?year=2026&month=7"
curl "http://127.0.0.1:8087/api/reports/monthly-trend?year=2026&deptId=10"
curl "http://127.0.0.1:8087/api/reports/personal-summary?userId=1&year=2026"
```
**期望**: 全部 200, 空数据返回空列表而非 null

---

## 八、Gateway

### 8.1 登录转发
```bash
curl -X POST http://127.0.0.1:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```
**期望**: 200 (Gateway 转发了请求)

### 8.2 内部接口隔离
```bash
curl http://127.0.0.1:8080/internal/users/1
```
**期望**: 404 (/internal/** 不在路由表)

### 8.3 无 Token 被拒
```bash
curl http://127.0.0.1:8080/api/reimbursements
```
**期望**: 401

---

## 九、验收检查清单

- [ ] MySQL + Nacos + Redis + RabbitMQ 全在线
- [ ] 登录: 200 + JWT
- [ ] 错误登录: 10501
- [ ] Token 刷新: 200
- [ ] 创建报销单: 200 + DRAFT
- [ ] 提交报销单: 200 + PENDING
- [ ] 无 Token: 401
- [ ] 预算冻结: success=true
- [ ] 预算超额冻结: success=false, "预算不足"
- [ ] 预算消费/解冻: 200
- [ ] 审批链: 金额分级正确
- [ ] 审批通过/驳回: 200
- [ ] OCR 同步识别: totalAmount 非空
- [ ] OCR 缓存命中: 无重复百度调用
- [ ] 通知: MQ → 数据库有消息
- [ ] 报表 5 个接口: 200
- [ ] Gateway /internal/**: 404
- [ ] 前端登录页: http://localhost:3000 可打开
