package com.costlink.reimbursement.config;

import com.costlink.common.dto.Result;
import com.costlink.common.feign.ApprovalClient;
import com.costlink.common.feign.BudgetClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.math.BigDecimal;

@Configuration
@Profile("mock")
public class MockFeignConfig {

    @Bean
    public BudgetClient mockBudgetClient() {
        return new BudgetClient() {
            @Override
            public Result<BudgetClient.FreezeResponse> freeze(BudgetClient.FreezeRequest req) {
                BudgetClient.FreezeResponse resp = new BudgetClient.FreezeResponse();
                resp.setSuccess(true);
                resp.setAvailableAfterFreeze(new BigDecimal("50000"));
                resp.setControlStrategy("STRICT");
                resp.setMessage("Mock: 预算冻结成功");
                return Result.ok(resp);
            }
            @Override
            public Result<Void> consume(BudgetClient.ConsumeRequest req) {
                return Result.ok();
            }
            @Override
            public Result<Void> unfreeze(BudgetClient.UnfreezeRequest req) {
                return Result.ok();
            }
            @Override
            public Result<BudgetClient.AvailableResponse> getAvailable(Long deptId, String cat) {
                BudgetClient.AvailableResponse resp = new BudgetClient.AvailableResponse();
                resp.setAvailableAmount(new BigDecimal("100000"));
                resp.setStatus("NORMAL");
                return Result.ok(resp);
            }
        };
    }

    @Bean
    public ApprovalClient mockApprovalClient() {
        return new ApprovalClient() {
            @Override
            public Result<ApprovalClient.StartResponse> start(ApprovalClient.StartRequest req) {
                ApprovalClient.StartResponse resp = new ApprovalClient.StartResponse();
                resp.setInstanceId(999L);
                resp.setCurrentApprover("Mock审批人");
                resp.setCurrentApproverId(1L);
                return Result.ok(resp);
            }
            @Override
            public Result<ApprovalClient.InstanceResponse> getInstance(Long instanceId) {
                return Result.ok(null);
            }
            @Override
            public Result<java.util.List<ApprovalClient.PendingItem>> getPending(Long approverId) {
                return Result.ok(java.util.List.of());
            }
        };
    }
}
