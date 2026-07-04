<template>
  <div>
    <h2 style="margin-bottom:16px">新建报销单</h2>
    <el-form :model="form" label-width="100px" style="max-width:800px">
      <el-form-item label="报销事由">
        <el-input v-model="form.title" placeholder="请输入报销事由" />
      </el-form-item>
      <el-form-item label="费用类型">
        <el-select v-model="form.expenseType" style="width:100%">
          <el-option label="差旅费" value="TRAVEL" />
          <el-option label="招待费" value="ENTERTAIN" />
          <el-option label="办公费" value="OFFICE" />
          <el-option label="交通费" value="TRANSPORT" />
          <el-option label="其他" value="OTHER" />
        </el-select>
      </el-form-item>

      <el-form-item label="发票上传">
        <div style="width:100%">
          <el-upload
            ref="uploadRef"
            :auto-upload="false"
            :on-change="handleFileChange"
            :limit="5"
            accept="image/jpeg,image/png,image/bmp"
            list-type="picture-card"
          >
            <el-icon><Plus /></el-icon>
          </el-upload>
          <div v-if="ocrResult" style="margin-top:8px;padding:8px 12px;background:#f0f9eb;border-radius:4px;font-size:13px">
            <p style="margin:0 0 4px;color:#67c23a">✅ OCR 识别完成</p>
            <p style="margin:0;color:#606266">发票号码: {{ ocrResult.invoiceNumber || '-' }} | 价税合计: ¥{{ ocrResult.totalAmount || '-' }}</p>
          </div>
        </div>
      </el-form-item>

      <el-form-item label="费用明细">
        <div style="width:100%">
          <div v-for="(item, i) in form.items" :key="i" class="item-row">
            <el-select v-model="item.category" placeholder="科目" style="width:160px">
              <el-option label="交通费" value="TRAVEL_TRANSPORT" />
              <el-option label="住宿费" value="TRAVEL_ACCOMMODATION" />
              <el-option label="餐饮费" value="ENTERTAIN_MEAL" />
              <el-option label="办公用品" value="OFFICE_SUPPLIES" />
            </el-select>
            <el-input-number v-model="item.amount" :precision="2" :min="0" style="width:160px" />
            <el-date-picker v-model="item.receiptDate" type="date" placeholder="票据日期" style="width:140px" />
            <el-button type="danger" :icon="Delete" circle @click="form.items.splice(i,1)" />
          </div>
          <el-button type="primary" link @click="addItem">+ 添加明细</el-button>
        </div>
      </el-form-item>

      <el-form-item label="备注">
        <el-input v-model="form.remark" type="textarea" rows="3" />
      </el-form-item>

      <el-form-item>
        <el-button type="primary" @click="handleSave" :loading="saving">保存草稿</el-button>
        <el-button type="success" @click="handleSubmit" :loading="submitting">提交审批</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { create, submit } from '@/api/reimbursement'
import { ElMessage } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import axios from 'axios'

const router = useRouter()
const saving = ref(false)
const submitting = ref(false)
const ocrResult = ref(null)
const uploadRef = ref(null)

const form = reactive({
  title: '', expenseType: 'TRAVEL', remark: '',
  items: [{ category: 'TRAVEL_TRANSPORT', amount: 0, receiptDate: '' }]
})

function addItem() { form.items.push({ category: '', amount: 0, receiptDate: '' }) }

async function handleFileChange(file) {
  const reader = new FileReader()
  reader.readAsDataURL(file.raw)
  reader.onload = async () => {
    const base64Url = reader.result
    const fileHash = md5(base64Url)
    form.currentAttachment = { fileUrl: base64Url, fileHash, fileName: file.name }
    try {
      const res = await axios.post('/api/ocr/recognize', {
        attachmentId: Date.now(), fileHash, fileUrl: base64Url
      })
      if (res.data?.code === 200 && res.data?.data) {
        ocrResult.value = res.data.data
        const amount = parseFloat(res.data.data.totalAmount)
        if (amount > 0 && form.items[0]?.amount === 0) {
          form.items[0].amount = amount
        }
        ElMessage.success('OCR 识别成功')
      }
    } catch (e) {
      ElMessage.warning('OCR 识别暂不可用（需配置百度凭据）')
    }
  }
}

function md5(str) {
  let hash = 0
  for (let i = 0; i < str.length; i++) { const c = str.charCodeAt(i); hash = ((hash << 5) - hash) + c; hash |= 0 }
  return Math.abs(hash).toString(16)
}

async function handleSave() {
  saving.value = true
  try {
    const res = await create({ ...form })
    ElMessage.success(`草稿已保存，编号: ${res.id}`)
    router.push(`/reimbursements/${res.id}`)
  } catch (e) { ElMessage.error(e) } finally { saving.value = false }
}

async function handleSubmit() {
  submitting.value = true
  try {
    const res = await create({ ...form })
    await submit(res.id)
    ElMessage.success('报销单已提交审批')
    router.push(`/reimbursements/${res.id}`)
  } catch (e) { ElMessage.error(e) } finally { submitting.value = false }
}
</script>

<style scoped>
.item-row { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
</style>
