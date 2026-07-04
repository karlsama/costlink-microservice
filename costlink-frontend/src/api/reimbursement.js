import request from './request'

export function getList(params) {
  return request.get('/reimbursements', { params })
}

export function getById(id) {
  return request.get(`/reimbursements/${id}`)
}

export function create(data) {
  return request.post('/reimbursements', data)
}

export function update(id, data) {
  return request.put(`/reimbursements/${id}`, data)
}

export function submit(id) {
  return request.post(`/reimbursements/${id}/submit`)
}

export function withdraw(id) {
  return request.post(`/reimbursements/${id}/withdraw`)
}

export function remove(id) {
  return request.delete(`/reimbursements/${id}`)
}
