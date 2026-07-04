import axios from 'axios'
import { useAuthStore } from '@/stores/auth'
import router from '@/router'

const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 请求拦截器 — 自动带 Token
request.interceptors.request.use(config => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

// 响应拦截器 — 401 尝试刷新 Token
let isRefreshing = false
let refreshSubscribers = []

function onRefreshed(newToken) {
  refreshSubscribers.forEach(cb => cb(newToken))
  refreshSubscribers = []
}

request.interceptors.response.use(
  response => response.data,
  async error => {
    const originalRequest = error.config

    // 401 → 尝试刷新
    if (error.response?.status === 401 && !originalRequest._retry) {
      if (isRefreshing) {
        // 其他请求排队等刷新完成
        return new Promise(resolve => {
          refreshSubscribers.push(token => {
            originalRequest.headers.Authorization = `Bearer ${token}`
            resolve(request(originalRequest))
          })
        })
      }

      originalRequest._retry = true
      isRefreshing = true

      try {
        const auth = useAuthStore()
        await auth.refreshToken()
        isRefreshing = false
        onRefreshed(auth.token)
        originalRequest.headers.Authorization = `Bearer ${auth.token}`
        return request(originalRequest)
      } catch {
        isRefreshing = false
        refreshSubscribers = []
        const auth = useAuthStore()
        auth.logout()
        router.push('/login')
        return Promise.reject(error)
      }
    }

    const msg = error.response?.data?.message || error.message || '网络错误'
    return Promise.reject(msg)
  }
)

export default request
