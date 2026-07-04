<template>
  <div>
    <h2>系统管理</h2>

    <el-row :gutter="16" style="margin-bottom:16px">
      <el-col :span="12">
        <el-card>
          <template #header>账号操作</template>
          <div style="display:flex;gap:12px">
            <el-button type="primary" @click="$router.push('/settings/profile')">个人空间</el-button>
            <el-button type="danger" @click="handleLogout">退出登录</el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card style="margin-bottom:16px">
      <template #header>当前用户</template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="用户ID">{{ auth.userId }}</el-descriptions-item>
        <el-descriptions-item label="用户名">{{ auth.userName }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ auth.userRole }}</el-descriptions-item>
        <el-descriptions-item label="部门ID">{{ auth.departmentId }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card style="margin-bottom:16px">
      <template #header>系统信息</template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="系统版本">1.0.0</el-descriptions-item>
        <el-descriptions-item label="前端框架">Vue 3 + Element Plus</el-descriptions-item>
        <el-descriptions-item label="后端架构">Spring Cloud 微服务</el-descriptions-item>
        <el-descriptions-item label="API 网关">http://localhost:8080</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card>
      <template #header>功能模块</template>
      <el-table :data="modules" border stripe>
        <el-table-column prop="name" label="模块" width="120" />
        <el-table-column prop="desc" label="说明" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === '已就绪' ? 'success' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ElMessageBox } from 'element-plus'

const router = useRouter()
const auth = useAuthStore()

const modules = [
  { name: '报销管理', desc: '报销单创建、提交、查询、删除', status: '已就绪' },
  { name: '审批管理', desc: '审批链启动、审批通过/驳回/转审', status: '已就绪' },
  { name: '预算管理', desc: '预算冻结/消费/解冻、执行率查询', status: '已就绪' },
  { name: '数据报表', desc: '跨库汇总、月度趋势、部门排行', status: '已就绪' },
  { name: 'OCR 识别', desc: '百度增值税发票识别', status: '待配置' },
  { name: '通知消息', desc: '审批通知、预算预警', status: '待配置' },
]

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确认退出登录？', '提示')
    auth.logout()
    router.push('/login')
  } catch { /* 取消 */ }
}
</script>
