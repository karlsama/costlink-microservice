package com.costlink.reimbursement.service;

import com.costlink.common.dto.Result;
import com.costlink.reimbursement.dto.ReimbursementCreateRequest;
import com.costlink.reimbursement.dto.ReimbursementUpdateRequest;
import com.costlink.reimbursement.entity.Reimbursement;

public interface ReimbursementService {
    Result<Reimbursement> create(ReimbursementCreateRequest request, Long userId, Long departmentId);
    Result<Reimbursement> update(Long id, ReimbursementUpdateRequest request, Long userId);
    Result<Reimbursement> getById(Long id);
    Result<?> page(int page, int size, String status, Long userId);
    Result<Reimbursement> submit(Long id, Long userId);
    Result<Reimbursement> withdraw(Long id, Long userId);
    Result<Reimbursement> markPaid(Long id, Long userId, Long operatorId);
    Result<Void> delete(Long id, Long userId);
}
