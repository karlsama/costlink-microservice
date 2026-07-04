import request from './request'

export function getAvailable(params) {
  return request.get('/budgets/available', { params })
}

export function getExecuteReport(params) {
  return request.get('/budgets/execute-report', { params })
}

export function getList(params) {
  return request.get('/budgets', { params })
}

export function create(data) {
  return request.post('/budgets', data)
}
