# costlink-frontend 开发指南

> 面向实际开发，一份文档写完前端。后端八个服务全部就绪，Gateway 统一端口 `http://localhost:8080`。
> 2026-07-04

---

## 1. 技术栈

| 层 | 选型 | 版本 |
|---|------|------|
| 框架 | Vue 3（Composition API） | 3.5+ |
| 构建 | Vite | 6.x |
| UI 库 | Element Plus | 2.9+ |
| 状态管理 | Pinia | 2.x |
| 路由 | Vue Router | 4.x |
| HTTP | Axios | 1.x |

**约束**：不使用 TypeScript，使用 JavaScript + Composition API（`<script setup>`）。`package.json` 放在 `costlink-frontend/` 下，`dist/` 已在 `.gitignore` 中。

---

## 2. 你要对接的后端——一张表看全

所有请求通过 Gateway 的 `http://localhost:8080`。Gateway 负责 JWT 鉴权和路由转发。

| 页面 | 调用的接口 | 后端服务 |
|-----|----------|---------|
| 登录 | `POST /api/auth/login` | 认证 |
| 登录 | `POST /api/auth/refresh` | 认证 |
| 报销列表 | `GET /api/reimbursements?page=&size=&status=` | 报销 |
| 新建报销 | `POST /api/reimbursements` | 报销 |
| 报销详情 | `GET /api/reimbursements/{id}` | 报销 |
| 上传发票 | `POST /api/reimbursements/{id}/attachments` | 报销 → OCR |
| 提交报销 | `POST /api/reimbursements/{id}/submit` | 报销 → 预算 + 审批 |
| 审批待办 | `GET /api/approvals/pending?approverId=` | 审批 |
| 审批通过 | `POST /api/approvals/{instanceId}/approve` | 审批 |
| 审批驳回 | `POST /api/approvals/{instanceId}/reject` | 审批 |
| 预算看板 | `GET /api/budgets/execute-report` | 预算 |
| 报表页 | `GET /api/reports/reimbursement-summary` 等 5 个 | 报表 |
| 消息通知 | `GET /api/notifications?userId=...` | 通知（注：开发未实现查询接口，前端需手动加） |

---

## 3. 项目搭建

```bash
cd F:\project_007
npm create vite@latest costlink-frontend -- --template vue
cd costlink-frontend
npm install
npm install vue-router@4 pinia@2 axios element-plus @element-plus/icons-vue
```

`vite.config.js` 加代理（开发阶段跨域）：

```js
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
```

**启动**：

```bash
npm run dev
```

前端在 `http://localhost:3000`。代理把所有 `/api/*` 请求转发到 Gateway `:8080`。

---

## 4. 目录结构

```
costlink-frontend/
├── index.html
├── vite.config.js
├── package.json
└── src/
    ├── main.js                    ← 挂载 App + 注册路由 + Pinia + Element Plus
    ├── App.vue                    ← <router-view/>
    ├── router/
    │   └── index.js               ← 路由表 + beforeEach 守卫
    ├── stores/
    │   ├── auth.js                ← Token + 用户信息
    │   └── notification.js        ← 消息轮询（可选）
    ├── api/
    │   ├── request.js             ← axios 实例 + 拦截器（Token 注入、过期刷新）
    │   ├── auth.js                ← login / refresh / logout
    │   ├── reimbursement.js       ← 报销 CRUD + 附件上传 + 提交
    │   ├── approval.js            ← 待办列表 + 审批操作
    │   ├── budget.js              ← 预算看板
    │   └── report.js              ← 5 个报表接口
    ├── views/
    │   ├── LoginView.vue          ← 登录页
    │   ├── LayoutView.vue         ← 主布局（侧边栏 + 顶部栏）
    │   ├── reimbursement/
    │   │   ├── ReimbursementList.vue
    │   │   ├── ReimbursementCreate.vue
    │   │   └── ReimbursementDetail.vue
    │   ├── approval/
    │   │   └── ApprovalPending.vue
    │   ├── budget/
    │   │   └── BudgetDashboard.vue
    │   ├── report/
    │   │   └── ReportView.vue
    │   └── settings/
    │       └── SettingsView.vue
    └── components/
        ├── AppSidebar.vue
        └── AppHeader.vue
```

---

## 5. 路由设计

```js
// router/index.js
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  { path: '/login', name: 'Login', component: () => import('@/views/LoginView.vue'),
    meta: { noAuth: true } },

  { path: '/', component: () => import('@/views/LayoutView.vue'),
    children: [
      { path: '',                redirect: '/reimbursements' },
      { path: 'reimbursements',  name: 'ReimbursementList',   component: () => import('@/views/reimbursement/ReimbursementList.vue') },
      { path: 'reimbursements/new', name: 'ReimbursementCreate', component: () => import('@/views/reimbursement/ReimbursementCreate.vue') },
      { path: 'reimbursements/:id', name: 'ReimbursementDetail', component: () => import('@/views/reimbursement/ReimbursementDetail.vue') },
      { path: 'approvals',       name: 'ApprovalPending',  component: () => import('@/views/approval/ApprovalPending.vue') },
      { path: 'budget',          name: 'BudgetDashboard',  component: () => import('@/views/budget/BudgetDashboard.vue') },
      { path: 'reports',         name: 'ReportView',       component: () => import('@/views/report/ReportView.vue') },
      { path: 'settings',        name: 'SettingsView',     component: () => import('@/views/settings/SettingsView.vue') },
    ]
  }
]

const router = createRouter({ history: createWebHistory(), routes })

// === 路由守卫 — 未登录跳 /login ===
router.beforeEach((to, from, next) => {
  const auth = useAuthStore()
  if (!to.meta.noAuth && !auth.token) {
    return next('/login')
  }
  next()
})

export default router
```

---

## 6. API 层——Axios 拦截器

```js
// api/request.js
import axios from 'axios'
import { useAuthStore } from '@/stores/auth'
import router from '@/router'

const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 请求拦截器 — 自动带 Token
request.interceptors.request.use(config => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

// 响应拦截器 — 401 时尝试刷新 Token
request.interceptors.response.use(
  response => response.data,             // 直接返回 Result.data
  async error => {
    if (error.response?.status === 401) {
      const auth = useAuthStore()
      try {
        await auth.refreshToken()
        // 刷新成功，用新 Token 重试原请求
        error.config.headers.Authorization = `Bearer ${auth.token}`
        return request(error.config)
      } catch {
        // 刷新失败 → 踢回登录页
        auth.logout()
        router.push('/login')
      }
    }
    return Promise.reject(error)
  }
)

export default request
```

**关键行为**：拦截器 `response => response.data`——所有 API 调用返回的结果自动解包一层，拿到的是 `Result.data` 里面的内容，不需要每次调用后手动 `.data`。

---

## 7. 状态管理——Auth Store

```js
// stores/auth.js
import { defineStore } from 'pinia'
import { login as loginApi, refresh as refreshApi } from '@/api/auth'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('accessToken') || '',
    refreshToken: localStorage.getItem('refreshToken') || '',
    user: JSON.parse(localStorage.getItem('user') || 'null')
  }),

  actions: {
    async login(username, password) {
      const res = await loginApi(username, password)
      this.token = res.accessToken
      this.refreshToken = res.refreshToken
      this.user = res.userInfo
      localStorage.setItem('accessToken', res.accessToken)
      localStorage.setItem('refreshToken', res.refreshToken)
      localStorage.setItem('user', JSON.stringify(res.userInfo))
    },

    async refreshToken() {
      const res = await refreshApi(this.refreshToken)
      this.token = res.accessToken
      localStorage.setItem('accessToken', res.accessToken)
    },

    logout() {
      this.token = ''
      this.refreshToken = ''
      this.user = null
      localStorage.clear()
    }
  }
})
```

---

## 8. 页面功能说明

### 8.1 登录页 (`LoginView.vue`)

一个居中卡片，用户名密码输入框 + 登录按钮。调用 `authStore.login()`。成功后 `router.push('/')`。失败显示 Element Plus 的 `ElMessage.error`。

### 8.2 报销列表 (`ReimbursementList.vue`)

- 顶部：状态筛选（全部 / DRAFT / PENDING / APPROVED）+ "新建报销" 按钮
- 表格：报销事由、金额、状态、提交时间、操作
- 分页：Element Plus `ElPagination`
- API：`GET /api/reimbursements?page=&size=&status=`

### 8.3 新建报销 (`ReimbursementCreate.vue`)

- 报销事由输入框
- 费用类型下拉（TRAVEL / ENTERTAIN / OFFICE / TRANSPORT / OTHER）
- 费用明细列表（每行：科目下拉 + 金额 + 日期 + 备注，可增删行）
- 发票上传区（`ElUpload` → FileReader 转 Base64 → 调附件上传接口）
- 保存草稿：`POST /api/reimbursements`
- 提交审批：`POST /api/reimbursements/{id}/submit`

### 8.4 审批待办 (`ApprovalPending.vue`)

- 表格：报销事由、申请人、金额、提交时间
- 每行操作：通过 / 驳回按钮
- 点击进入审批详情（弹窗或内联展开）：审批链展示 + 审批意见输入框
- API：`GET /api/approvals/pending?approverId=` → `POST /api/approvals/{id}/approve|reject`

### 8.5 预算看板 (`BudgetDashboard.vue`)

- 部门选择下拉
- 卡片行：总预算、已使用、冻结中、可用余额（Element Plus `ElStatistic`）
- 科目表格：科目名、预算额、已用、执行率、状态
- API：`GET /api/budgets/execute-report?departmentId=&fiscalYear=`

### 8.6 报表页 (`ReportView.vue`)

- 顶部：年/月/部门筛选
- 报销汇总卡片
- 月度趋势柱状图（用 Chart.js 或 ECharts）
- 部门排行表格
- 个人汇总（当前登录用户）
- API：5 个报表接口，Service 层聚合后按需展示

### 8.7 系统管理 (`SettingsView.vue`) — 可选

管理员界面：人员列表、费用科目维护、审批模板查看。后端没有对应的管理接口，可以放占位页面。

---

## 9. 前端构建部署

```bash
npm run build           # 产出 dist/
```

`dist/` 的内容由 Nginx 托管。docker-compose.yml 已配好挂载：

```yaml
nginx:
  image: nginx:1.25-alpine
  ports: ["80:80"]
  volumes:
    - ./costlink-frontend/dist:/usr/share/nginx/html:ro
    - ./nginx.conf:/etc/nginx/conf.d/default.conf:ro
```

`nginx.conf` 把 `/api/` 代理到 Gateway：

```nginx
server {
    listen 80;
    location / {
        root /usr/share/nginx/html;
        try_files $uri /index.html;
    }
    location /api/ {
        proxy_pass http://host.docker.internal:8080;
    }
}
```

---

## 10. 检查清单

- [ ] `npm run dev` 启动后 http://localhost:3000 能打开
- [ ] 登录页 → 输入 admin/admin123 → 跳转到首页
- [ ] 新建报销单 → 保存草稿 → 列表里能看到
- [ ] 上传发票 → 附件记录创建成功
- [ ] 提交报销 → 状态变为 PENDING（Mock 模式验证）
- [ ] 审批待办 → 能看到提交的报销单 → 审批通过/驳回
- [ ] 预算看板 → 显示预算执行率
- [ ] 报表页 → 查询返回正确数据
- [ ] Token 过期 → axios 拦截器自动刷新 → 如果 refresh 也过期踢回登录页
