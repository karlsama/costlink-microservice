import request from './request'

export function getPending(approverId) {
  return request.get('/approvals/pending', { params: { approverId } })
}

export function approve(instanceId, data) {
  return request.post(`/approvals/${instanceId}/approve`, data)
}

export function reject(instanceId, data) {
  return request.post(`/approvals/${instanceId}/reject`, data)
}

export function transfer(instanceId, data) {
  return request.post(`/approvals/${instanceId}/transfer`, data)
}

export function getInstanceDetail(id) {
  return request.get(`/approvals/instances/${id}`)
}
