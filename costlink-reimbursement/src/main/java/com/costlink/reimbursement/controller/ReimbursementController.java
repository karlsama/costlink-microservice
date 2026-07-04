package com.costlink.reimbursement.controller;

import com.costlink.common.dto.Result;
import com.costlink.common.dto.UserContext;
import com.costlink.reimbursement.dto.ReimbursementCreateRequest;
import com.costlink.reimbursement.dto.ReimbursementUpdateRequest;
import com.costlink.reimbursement.entity.Reimbursement;
import com.costlink.reimbursement.service.ReimbursementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reimbursements")
@RequiredArgsConstructor
public class ReimbursementController {

    private final ReimbursementService reimbursementService;

    @PostMapping
    public Result<Reimbursement> create(@Valid @RequestBody ReimbursementCreateRequest request) {
        return reimbursementService.create(request,
                UserContext.getUserId(), UserContext.getDepartmentId());
    }

    @PutMapping("/{id}")
    public Result<Reimbursement> update(@PathVariable Long id,
                                        @Valid @RequestBody ReimbursementUpdateRequest request) {
        return reimbursementService.update(id, request, UserContext.getUserId());
    }

    @GetMapping("/{id}")
    public Result<Reimbursement> getById(@PathVariable Long id) {
        return reimbursementService.getById(id);
    }

    @GetMapping
    public Result<?> page(@RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "10") int size,
                          @RequestParam(required = false) String status) {
        return reimbursementService.page(page, size, status, UserContext.getUserId());
    }

    @PostMapping("/{id}/submit")
    public Result<Reimbursement> submit(@PathVariable Long id) {
        return reimbursementService.submit(id, UserContext.getUserId());
    }

    @PostMapping("/{id}/withdraw")
    public Result<Reimbursement> withdraw(@PathVariable Long id) {
        return reimbursementService.withdraw(id, UserContext.getUserId());
    }

    @PostMapping("/{id}/mark-paid")
    public Result<Reimbursement> markPaid(@PathVariable Long id) {
        return reimbursementService.markPaid(id, UserContext.getUserId(), UserContext.getUserId());
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        return reimbursementService.delete(id, UserContext.getUserId());
    }
}
