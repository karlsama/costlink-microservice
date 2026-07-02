package com.costlink.common.feign;

import com.costlink.common.dto.Result;
import lombok.Data;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Feign 接口 — 认证服务（审批服务/网关调用）
 */
@FeignClient(name = "costlink-auth", path = "/internal/users")
public interface AuthClient {

    /** 查询用户信息 */
    @GetMapping("/{userId}")
    Result<UserInfoDTO> getUserById(@PathVariable Long userId);

    /** 根据角色查用户列表（审批链路由时使用） */
    @GetMapping("/by-role")
    Result<List<UserInfoDTO>> getUsersByRole(
            @RequestParam String role,
            @RequestParam(required = false) Long departmentId);

    /** 查询部门下的用户 */
    @GetMapping("/by-department/{departmentId}")
    Result<List<UserInfoDTO>> getUsersByDepartment(@PathVariable Long departmentId);

    // ---------- DTO ----------

    @Data
    class UserInfoDTO {
        private Long id;
        private String username;
        private String displayName;
        private String role;
        private Long departmentId;
        private String departmentName;
        private String email;
    }
}
