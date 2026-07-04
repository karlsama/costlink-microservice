import request from './request'

export function login(username, password) {
  return request.post('/auth/login', { username, password })
}

export function refresh(refreshToken) {
  return request.post('/auth/refresh', { refreshToken })
}
