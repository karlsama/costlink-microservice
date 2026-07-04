<template>
  <div>
    <h2>工作台</h2>

    <el-row :gutter="16" style="margin-bottom:20px">
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-item">
            <p class="stat-label">待审批</p>
            <p class="stat-value">{{ pendingCount }}</p>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-item">
            <p class="stat-label">报销总额（本月）</p>
            <p class="stat-value">¥{{ monthlyTotal }}</p>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-item">
            <p class="stat-label">我的报销单</p>
            <p class="stat-value">{{ myCount }}</p>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-item">
            <p class="stat-label">可用预算</p>
            <p class="stat-value">¥{{ availableBudget }}</p>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16">
      <el-col :span="12">
        <el-card>
          <template #header>快捷操作</template>
          <div style="display:flex;gap:12px;flex-wrap:wrap">
            <el-button type="primary" @click="$router.push('/reimbursements/new')">新建报销</el-button>
            <el-button @click="$router.push('/approvals')">审批待办</el-button>
            <el-button @click="$router.push('/budget')">预算看板</el-button>
            <el-button @click="$router.push('/reports')">数据报表</el-button>
          </div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>系统信息</template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="当前用户">{{ auth.userName }}</el-descriptions-item>
            <el-descriptions-item label="角色">{{ auth.userRole }}</el-descriptions-item>
            <el-descriptions-item label="部门ID">{{ auth.departmentId }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { getList } from '@/api/reimbursement'
import { getPending } from '@/api/approval'
import { getExecuteReport } from '@/api/budget'

const auth = useAuthStore()
const pendingCount = ref(0)
const monthlyTotal = ref(0)
const myCount = ref(0)
const availableBudget = ref(0)

onMounted(async () => {
  try {
    const [pendingList, myList, budgetReport] = await Promise.all([
      getPending(auth.userId).catch(() => []),
      getList({ page: 1, size: 1, status: undefined }).catch(() => ({ total: 0 })),
      getExecuteReport({ departmentId: auth.departmentId, fiscalYear: new Date().getFullYear() }).catch(() => [])
    ])
    pendingCount.value = Array.isArray(pendingList) ? pendingList.length : 0
    myCount.value = myList.total || 0
    if (Array.isArray(budgetReport)) {
      const total = budgetReport.reduce((s, l) => s + (l.totalAmount || 0) - (l.usedAmount || 0) - (l.frozenAmount || 0), 0)
      availableBudget.value = total
    }
  } catch (e) { /* 静默处理 */ }
})
</script>

<style scoped>
.stat-item { text-align: center; }
.stat-label { color: #909399; margin: 0 0 8px; font-size: 14px; }
.stat-value { font-size: 28px; font-weight: bold; color: #303133; margin: 0; }
</style>
