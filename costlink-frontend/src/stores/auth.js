import { defineStore } from 'pinia'
import request from '@/api/request'

export const useAuthStore = defineStore('auth', {
  state: () => {
    const rawToken = localStorage.getItem('accessToken')
    const rawRefresh = localStorage.getItem('refreshToken')
    let savedUser = null
    try { savedUser = JSON.parse(localStorage.getItem('user') || 'null') } catch { savedUser = null }
    return {
      token: rawToken && rawToken !== 'undefined' ? rawToken : '',
      refreshToken: rawRefresh && rawRefresh !== 'undefined' ? rawRefresh : '',
      user: savedUser
    }
  },

  getters: {
    isLoggedIn:  (state) => !!state.token,
    userId:      (state) => state.user?.id,
    userName:    (state) => state.user?.displayName,
    userRole:    (state) => state.user?.role,
    departmentId:(state) => state.user?.departmentId
  },

  actions: {
    async login(username, password) {
      const res = await request.post('/auth/login', { username, password })
      const d = res.data || res
      this.token = d.accessToken
      this.refreshToken = d.refreshToken
      this.user = d.userInfo
      localStorage.setItem('accessToken', d.accessToken)
      localStorage.setItem('refreshToken', d.refreshToken)
      localStorage.setItem('user', JSON.stringify(d.userInfo))
    },

    async refreshToken() {
      const res = await request.post('/auth/refresh', {
        refreshToken: this.refreshToken
      })
      const d = res.data || res
      this.token = d.accessToken
      localStorage.setItem('accessToken', d.accessToken)
    },

    logout() {
      this.token = ''
      this.refreshToken = ''
      this.user = null
      localStorage.clear()
    }
  }
})
