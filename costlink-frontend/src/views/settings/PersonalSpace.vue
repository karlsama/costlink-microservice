<template>
  <div>
    <h2>个人空间</h2>

    <el-card style="margin-bottom:16px">
      <template #header>个人信息</template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="用户ID">{{ auth.userId }}</el-descriptions-item>
        <el-descriptions-item label="用户名">{{ auth.userName }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ auth.userRole }}</el-descriptions-item>
        <el-descriptions-item label="部门ID">{{ auth.departmentId }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card>
      <template #header>我的报销统计</template>
      <el-row :gutter="16">
        <el-col :span="8">
          <div class="stat-box">
            <p class="stat-num">{{ myStats.totalCount }}</p>
            <p class="stat-lbl">总报销单数</p>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat-box">
            <p class="stat-num">¥{{ myStats.totalAmount }}</p>
            <p class="stat-lbl">总报销金额</p>
          </div>
        </el-col>
        <el-col :span="8">
          <div class="stat-box">
            <p class="stat-num">¥{{ myStats.paidAmount }}</p>
            <p class="stat-lbl">已到账金额</p>
          </div>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { personalSummary } from '@/api/report'

const auth = useAuthStore()
const myStats = ref({ totalCount: 0, totalAmount: 0, paidAmount: 0 })

onMounted(async () => {
  try {
    const res = await personalSummary({ userId: auth.userId, year: new Date().getFullYear() })
    if (res) myStats.value = res
  } catch { /* 静默 */ }
})
</script>

<style scoped>
.stat-box { text-align: center; padding: 20px 0; }
.stat-num { font-size: 28px; font-weight: bold; color: #303133; margin: 0 0 8px; }
.stat-lbl { color: #909399; margin: 0; font-size: 14px; }
</style>
