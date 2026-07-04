<template>
  <div>
    <h2>预算看板</h2>
    <el-row :gutter="16" style="margin-bottom:20px">
      <el-col :span="6" v-for="card in statCards" :key="card.label">
        <el-card shadow="hover">
          <p style="color:#909399;margin:0 0 4px">{{ card.label }}</p>
          <p style="font-size:24px;font-weight:bold;margin:0;color:#303133">¥{{ card.value }}</p>
        </el-card>
      </el-col>
    </el-row>
    <h3>科目明细</h3>
    <el-table :data="lines" border stripe v-loading="loading">
      <el-table-column prop="category" label="费用科目" />
      <el-table-column prop="totalAmount" label="预算总额">
        <template #default="{ row }">¥{{ row.totalAmount }}</template>
      </el-table-column>
      <el-table-column prop="usedAmount" label="已使用">
        <template #default="{ row }">¥{{ row.usedAmount }}</template>
      </el-table-column>
      <el-table-column prop="frozenAmount" label="冻结中">
        <template #default="{ row }">¥{{ row.frozenAmount }}</template>
      </el-table-column>
      <el-table-column prop="executeRate" label="执行率">
        <template #default="{ row }">{{ row.executeRate }}%</template>
      </el-table-column>
      <el-table-column label="状态">
        <template #default="{ row }">
          <el-tag :type="row.executeRate > 80 ? 'danger' : row.executeRate > 50 ? 'warning' : 'success'">
            {{ row.executeRate > 80 ? '紧张' : row.executeRate > 50 ? '正常' : '充足' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { getExecuteReport } from '@/api/budget'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const lines = ref([])
const loading = ref(false)

const statCards = computed(() => [
  { label: '预算总额', value: lines.value.reduce((s, l) => s + (l.totalAmount || 0), 0) },
  { label: '已使用', value: lines.value.reduce((s, l) => s + (l.usedAmount || 0), 0) },
  { label: '冻结中', value: lines.value.reduce((s, l) => s + (l.frozenAmount || 0), 0) },
  { label: '可用余额', value: lines.value.reduce((s, l) => s + (l.totalAmount || 0) - (l.usedAmount || 0) - (l.frozenAmount || 0), 0) }
])

onMounted(fetchData)
async function fetchData() {
  loading.value = true
  try {
    const res = await getExecuteReport({ departmentId: auth.departmentId, fiscalYear: 2026 })
    lines.value = Array.isArray(res) ? res : []
  } finally { loading.value = false }
}
</script>
