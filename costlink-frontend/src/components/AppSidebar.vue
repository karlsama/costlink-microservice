<template>
  <el-aside width="220px" class="sidebar">
    <div class="sidebar-logo" @click="$router.push('/')" style="cursor:pointer">CostLink</div>
    <el-menu
      :default-active="route.path"
      router
      background-color="#304156"
      text-color="#bfcbd9"
      active-text-color="#409eff"
    >
      <el-menu-item index="/">
        <el-icon><HomeFilled /></el-icon>
        <span>工作台</span>
      </el-menu-item>
      <el-menu-item index="/reimbursements">
        <el-icon><Document /></el-icon>
        <span>报销管理</span>
      </el-menu-item>
      <el-menu-item index="/approvals">
        <el-icon><Select /></el-icon>
        <span>审批待办</span>
      </el-menu-item>
      <el-menu-item index="/budget">
        <el-icon><Coin /></el-icon>
        <span>预算看板</span>
      </el-menu-item>
      <el-menu-item index="/reports">
        <el-icon><DataAnalysis /></el-icon>
        <span>数据报表</span>
      </el-menu-item>
      <el-menu-item index="/settings">
        <el-icon><Setting /></el-icon>
        <span>系统管理</span>
      </el-menu-item>
    </el-menu>
  </el-aside>
</template>

<script setup>
import { useRoute } from 'vue-router'
const route = useRoute()
</script>

<style scoped>
.sidebar {
  background: #304156;
  overflow-y: auto;
}
.sidebar-logo {
  height: 60px;
  line-height: 60px;
  text-align: center;
  color: #fff;
  font-size: 20px;
  font-weight: bold;
  letter-spacing: 2px;
  border-bottom: 1px solid rgba(255,255,255,0.1);
}
</style>
