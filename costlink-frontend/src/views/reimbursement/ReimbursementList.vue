<template>
  <div>
    <div class="page-header">
      <h2>报销管理</h2>
      <div class="header-actions">
        <el-select v-model="statusFilter" placeholder="状态筛选" clearable style="width:140px;margin-right:12px">
          <el-option label="全部" value="" />
          <el-option label="草稿" value="DRAFT" />
          <el-option label="审批中" value="PENDING" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
          <el-option label="已付款" value="PAID" />
        </el-select>
        <el-button type="primary" @click="$router.push('/reimbursements/new')">新建报销</el-button>
      </div>
    </div>
    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column prop="id" label="单号" width="180" />
      <el-table-column prop="title" label="事由" min-width="180" />
      <el-table-column prop="totalAmount" label="金额" width="120">
        <template #default="{ row }">¥{{ row.totalAmount }}</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="170" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="$router.push(`/reimbursements/${row.id}`)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="pagination-wrap">
      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="prev, pager, next, total"
        @current-change="fetchList"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { getList } from '@/api/reimbursement'

const list = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const statusFilter = ref('')

watch(statusFilter, () => { page.value = 1; fetchList() })

function statusTag(s) {
  const map = { DRAFT: 'info', PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', PAID: '' }
  return map[s] || 'info'
}
function statusLabel(s) {
  const map = { DRAFT: '草稿', PENDING: '审批中', APPROVED: '已通过', REJECTED: '已驳回', PAID: '已付款' }
  return map[s] || s
}

async function fetchList() {
  loading.value = true
  try {
    const res = await getList({ page: page.value, size: size.value, status: statusFilter.value || undefined })
    list.value = res.records || []
    total.value = res.total || 0
  } finally { loading.value = false }
}
fetchList()
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h2 { margin: 0; font-size: 20px; }
.header-actions { display: flex; align-items: center; }
.pagination-wrap { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>
