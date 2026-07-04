<template>
  <div v-loading="loading">
    <h2>报销单详情</h2>
    <el-descriptions v-if="detail" :column="2" border style="margin-top:16px">
      <el-descriptions-item label="单号">{{ detail.id }}</el-descriptions-item>
      <el-descriptions-item label="状态">
        <el-tag :type="tagType(detail.status)">{{ tagLabel(detail.status) }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="报销事由">{{ detail.title }}</el-descriptions-item>
      <el-descriptions-item label="总金额">¥{{ detail.totalAmount }}</el-descriptions-item>
      <el-descriptions-item label="费用类型">{{ detail.expenseType }}</el-descriptions-item>
      <el-descriptions-item label="提交时间">{{ detail.submitTime || '-' }}</el-descriptions-item>
      <el-descriptions-item label="备注" :span="2">{{ detail.remark || '-' }}</el-descriptions-item>
    </el-descriptions>

    <h3 style="margin-top:24px">费用明细</h3>
    <el-table :data="items" border stripe>
      <el-table-column prop="category" label="科目" />
      <el-table-column prop="amount" label="金额">
        <template #default="{ row }">¥{{ row.amount }}</template>
      </el-table-column>
      <el-table-column prop="receiptDate" label="票据日期" />
      <el-table-column prop="remark" label="备注" />
    </el-table>

    <div style="margin-top:24px; display:flex; gap:12px">
      <el-button v-if="detail?.status === 'DRAFT'" type="primary" @click="handleSubmit">提交审批</el-button>
      <el-button v-if="detail?.status === 'DRAFT'" @click="handleDelete">删除</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getById, submit as submitApi, remove } from '@/api/reimbursement'
import { ElMessage, ElMessageBox } from 'element-plus'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const detail = ref(null)
const items = ref([])

onMounted(fetchDetail)

function tagType(s) {
  return { DRAFT: 'info', PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', PAID: '' }[s] || 'info'
}
function tagLabel(s) {
  return { DRAFT: '草稿', PENDING: '审批中', APPROVED: '已通过', REJECTED: '已驳回', PAID: '已付款' }[s] || s
}

async function fetchDetail() {
  loading.value = true
  try {
    const res = await getById(route.params.id)
    detail.value = res
    items.value = res.expenseItems || []
  } finally { loading.value = false }
}

async function handleSubmit() {
  try {
    await submitApi(route.params.id)
    ElMessage.success('已提交审批')
    fetchDetail()
  } catch (e) { ElMessage.error(e) }
}

async function handleDelete() {
  try {
    await ElMessageBox.confirm('确认删除？')
    await remove(route.params.id)
    ElMessage.success('已删除')
    router.push('/reimbursements')
  } catch (e) { if (e !== 'cancel') ElMessage.error(e) }
}
</script>
