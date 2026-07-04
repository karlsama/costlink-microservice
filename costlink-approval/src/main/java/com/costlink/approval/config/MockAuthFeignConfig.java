package com.costlink.approval.config;

import com.costlink.common.dto.Result;
import com.costlink.common.feign.AuthClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Configuration
@Profile("mock")
public class MockAuthFeignConfig {

    @Bean
    @Primary
    public AuthClient mockAuthClient() {
        return new AuthClient() {
            @Override
            public Result<AuthClient.UserInfoDTO> getUserById(Long userId) {
                AuthClient.UserInfoDTO u = new AuthClient.UserInfoDTO();
                u.setId(userId);
                u.setDisplayName("Mock审批人");
                u.setRole("DEPARTMENT_HEAD");
                u.setDepartmentId(10L);
                return Result.ok(u);
            }
            @Override
            public Result<List<AuthClient.UserInfoDTO>> getUsersByRole(String role, Long departmentId) {
                AuthClient.UserInfoDTO u = new AuthClient.UserInfoDTO();
                u.setId(2L);
                u.setDisplayName("Mock审批人");
                u.setRole(role);
                u.setDepartmentId(departmentId);
                return Result.ok(List.of(u));
            }
            @Override
            public Result<List<AuthClient.UserInfoDTO>> getUsersByDepartment(Long departmentId) {
                return Result.ok(List.of());
            }
        };
    }
}
