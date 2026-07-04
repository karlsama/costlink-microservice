<template>
  <div>
    <h2>审批待办</h2>
    <el-table :data="list" border stripe v-loading="loading">
      <el-table-column prop="title" label="报销事由" min-width="160" />
      <el-table-column label="申请人" width="120">
        <template #default="{ row }">{{ row.applicantId }}</template>
      </el-table-column>
      <el-table-column label="金额" width="120">
        <template #default="{ row }">¥{{ row.amount }}</template>
      </el-table-column>
      <el-table-column prop="submitTime" label="提交时间" width="170" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="success" size="small" @click="handleApprove(row)">通过</el-button>
          <el-button type="danger" size="small" @click="handleReject(row)">驳回</el-button>
        </template>
      </el-table-column>
    </el-table>
    <p v-if="!loading && list.length === 0" style="color:#909399;text-align:center;margin-top:40px">暂无待审批任务</p>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="400px">
      <el-input v-model="dialogComment" type="textarea" rows="3" placeholder="审批意见（选填）" />
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmAction" :loading="actionLoading">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getPending, approve, reject } from '@/api/approval'
import { useAuthStore } from '@/stores/auth'
import { ElMessage } from 'element-plus'

const auth = useAuthStore()
const list = ref([])
const loading = ref(false)

const dialogVisible = ref(false)
const dialogTitle = ref('')
const dialogComment = ref('')
const currentAction = ref('')
const currentRow = ref(null)
const actionLoading = ref(false)

onMounted(fetchList)

async function fetchList() {
  loading.value = true
  try {
    list.value = await getPending(auth.userId)
  } finally { loading.value = false }
}

function handleApprove(row) {
  currentAction.value = 'approve'
  currentRow.value = row
  dialogTitle.value = '审批通过'
  dialogComment.value = ''
  dialogVisible.value = true
}

function handleReject(row) {
  currentAction.value = 'reject'
  currentRow.value = row
  dialogTitle.value = '审批驳回'
  dialogComment.value = ''
  dialogVisible.value = true
}

async function confirmAction() {
  actionLoading.value = true
  try {
    const fn = currentAction.value === 'approve' ? approve : reject
    await fn(currentRow.value.instanceId, { operatorId: auth.userId, comment: dialogComment.value })
    ElMessage.success(currentAction.value === 'approve' ? '已通过' : '已驳回')
    dialogVisible.value = false
    fetchList()
  } catch (e) { ElMessage.error(e) } finally { actionLoading.value = false }
}
</script>
