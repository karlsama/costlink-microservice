import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { noAuth: true }
  },
  {
    path: '/',
    component: () => import('@/views/LayoutView.vue'),
    children: [
      {
        path: '',
        name: 'Home',
        component: () => import('@/views/HomeView.vue'),
        meta: { title: '工作台' }
      },
      {
        path: 'reimbursements',
        name: 'ReimbursementList',
        component: () => import('@/views/reimbursement/ReimbursementList.vue'),
        meta: { title: '报销管理' }
      },
      {
        path: 'reimbursements/new',
        name: 'ReimbursementCreate',
        component: () => import('@/views/reimbursement/ReimbursementCreate.vue'),
        meta: { title: '新建报销' }
      },
      {
        path: 'reimbursements/:id',
        name: 'ReimbursementDetail',
        component: () => import('@/views/reimbursement/ReimbursementDetail.vue'),
        meta: { title: '报销详情' }
      },
      {
        path: 'approvals',
        name: 'ApprovalPending',
        component: () => import('@/views/approval/ApprovalPending.vue'),
        meta: { title: '审批待办' }
      },
      {
        path: 'budget',
        name: 'BudgetDashboard',
        component: () => import('@/views/budget/BudgetDashboard.vue'),
        meta: { title: '预算看板' }
      },
      {
        path: 'reports',
        name: 'Reports',
        component: () => import('@/views/report/ReportView.vue'),
        meta: { title: '数据报表' }
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('@/views/settings/SettingsView.vue'),
        meta: { title: '系统管理' }
      },
      {
        path: 'settings/profile',
        name: 'PersonalSpace',
        component: () => import('@/views/settings/PersonalSpace.vue'),
        meta: { title: '个人空间' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const auth = useAuthStore()
  if (!to.meta.noAuth && !auth.token) {
    return next('/login')
  }
  next()
})

export default router
