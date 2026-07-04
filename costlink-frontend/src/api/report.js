import request from './request'

export function reimbursementSummary(params) {
  return request.get('/reports/reimbursement-summary', { params })
}

export function budgetExecution(params) {
  return request.get('/reports/budget-execution', { params })
}

export function departmentRanking(params) {
  return request.get('/reports/department-ranking', { params })
}

export function monthlyTrend(params) {
  return request.get('/reports/monthly-trend', { params })
}

export function personalSummary(params) {
  return request.get('/reports/personal-summary', { params })
}
