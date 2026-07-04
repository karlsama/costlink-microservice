<template>
  <div>
    <h2>数据报表</h2>
    <el-card style="margin-bottom:16px">
      <el-form inline>
        <el-form-item label="年份">
          <el-select v-model="year" style="width:120px">
            <el-option label="2026" value="2026" />
            <el-option label="2025" value="2025" />
          </el-select>
        </el-form-item>
        <el-form-item label="月份">
          <el-select v-model="month" style="width:120px">
            <el-option v-for="m in 12" :key="m" :label="m + '月'" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="fetchAll">查询</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-row :gutter="16" style="margin-bottom:16px">
      <el-col :span="8" v-for="c in summaryCards" :key="c.label">
        <el-card shadow="hover">
          <p style="color:#909399;margin:0 0 4px">{{ c.label }}</p>
          <p style="font-size:22px;font-weight:bold;margin:0">¥{{ c.value }}</p>
        </el-card>
      </el-col>
    </el-row>

    <el-card title="部门费用排行" style="margin-bottom:16px">
      <el-table :data="ranking" border stripe>
        <el-table-column prop="departmentId" label="部门ID" />
        <el-table-column prop="count" label="报销次数" />
        <el-table-column label="总金额">
          <template #default="{ row }">¥{{ row.totalAmount }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { reimbursementSummary, departmentRanking } from '@/api/report'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const year = ref(2026)
const month = ref(7)
const summary = ref({})
const ranking = ref([])

const summaryCards = computed(() => [
  { label: '报销总额', value: summary.value.totalAmount || 0 },
  { label: '已通过', value: summary.value.approvedAmount || 0 },
  { label: '已付款', value: summary.value.paidAmount || 0 }
])

onMounted(fetchAll)

async function fetchAll() {
  try {
    summary.value = await reimbursementSummary({ deptId: auth.departmentId, year: year.value })
    ranking.value = await departmentRanking({ year: year.value, month: month.value })
  } catch (e) { /* ignore */ }
}
</script>
