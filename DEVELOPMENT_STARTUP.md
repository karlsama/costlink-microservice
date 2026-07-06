# CostLink 开发启动清单

> 每次开电脑开发时必须执行的操作，按顺序走。

---

## 每次必开的三个基础设施（不管开发哪个服务）

### 1. 启动 MySQL

管理员 PowerShell：

```powershell
cd "C:\Program Files\MySQL\MySQL Server 8.4\bin"
.\mysqld --defaults-file="C:\ProgramData\MySQL\my.ini" --console
```

不要关这个窗口。

### 2. 启动 Docker 中间件

WSL 终端：

```bash
cd /mnt/f/project_007
docker compose up nacos redis rabbitmq -d
```

### 3. 验证中间件都在线

- Nacos: http://127.0.0.1:8848/nacos （用户名密码 nacos/nacos）
- RabbitMQ: http://127.0.0.1:15672 （用户名密码 costlink/costlink123）

---

## 按需启动的服务（写哪个起哪个）

```powershell
cd F:\project_007

# 认证服务 (8084) — Gateway 依赖它做路由转发
cd costlink-auth && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Gateway (8080) — 前端依赖它做入口
cd costlink-gateway && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 报销服务 (8081) — Mock 模式
cd costlink-reimbursement && mvn spring-boot:run -Dspring-boot.run.profiles=dev,mock

# 预算服务 (8082)
cd costlink-budget && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 审批服务 (8083) — Mock 模式（不依赖 MQ）
cd costlink-approval && mvn spring-boot:run -Dspring-boot.run.profiles=dev,mock

# OCR 服务 (8085) — 需要设百度凭据环境变量
$env:BAIDU_OCR_API_KEY="7rkfAoOJffUKw2aLmjgIYFJo"
$env:BAIDU_OCR_SECRET_KEY="QRt53HFQSrkajHDsWjDE8YWw8ffTILon"
$env:BAIDU_OCR_APP_ID="123874271"
cd costlink-ocr && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 通知服务 (8086)
cd costlink-notification && mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 报表服务 (8087)
cd costlink-report && mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### 最小可跑链路（开发前端时至少需要）

```
MySQL + Nacos + Redis + RabbitMQ
    +
认证服务 (8084)  ← 登录
Gateway (8080)    ← 前端入口
报销服务 (8081)   ← 核心业务
```

---

## 前端开发

```powershell
cd F:\project_007\costlink-frontend
npm run dev
```

打开: http://localhost:3000

---

## 常用地址速查

| 用途 | 地址 |
|-----|------|
| 前端（开发） | http://localhost:3000 |
| Gateway（统一入口） | http://localhost:8080 |
| Nacos 控制台 | http://127.0.0.1:8848/nacos |
| RabbitMQ 管理 | http://127.0.0.1:15672 |
| 认证服务 | http://127.0.0.1:8084 |
| 报销服务 | http://127.0.0.1:8081 |
| 预算服务 | http://127.0.0.1:8082 |
| 审批服务 | http://127.0.0.1:8083 |
| OCR 服务 | http://127.0.0.1:8085 |
| 通知服务 | http://127.0.0.1:8086 |
| 报表服务 | http://127.0.0.1:8087 |

---

## 快速验证命令

```bash
# 登录拿 Token
curl -X POST http://127.0.0.1:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 查 Nacos 所有在线服务
curl http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=20
```
