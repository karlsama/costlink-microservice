package com.costlink.auth.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.costlink.auth.entity.User;
import com.costlink.auth.mapper.UserMapper;
import com.costlink.common.dto.Result;
import com.costlink.common.exception.BusinessException;
import com.costlink.common.exception.ErrorCode;
import com.costlink.common.feign.AuthClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserMapper userMapper;

    @GetMapping("/{userId}")
    public Result<AuthClient.UserInfoDTO> getUserById(@PathVariable Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.AUTH_USER_NOT_FOUND);
        }
        return Result.ok(toDTO(user));
    }

    @GetMapping("/by-role")
    public Result<List<AuthClient.UserInfoDTO>> getUsersByRole(
            @RequestParam String role,
            @RequestParam(required = false) Long departmentId) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
            .eq(User::getRole, role)
            .eq(User::getStatus, "ACTIVE");
        if (departmentId != null) {
            wrapper.eq(User::getDepartmentId, departmentId);
        }
        List<User> users = userMapper.selectList(wrapper);
        return Result.ok(users.stream().map(this::toDTO).toList());
    }

    @GetMapping("/by-department/{departmentId}")
    public Result<List<AuthClient.UserInfoDTO>> getUsersByDepartment(
            @PathVariable Long departmentId) {
        List<User> users = userMapper.selectList(
            new LambdaQueryWrapper<User>()
                .eq(User::getDepartmentId, departmentId)
                .eq(User::getStatus, "ACTIVE")
        );
        return Result.ok(users.stream().map(this::toDTO).toList());
    }

    private AuthClient.UserInfoDTO toDTO(User user) {
        AuthClient.UserInfoDTO dto = new AuthClient.UserInfoDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setDisplayName(user.getDisplayName());
        dto.setRole(user.getRole());
        dto.setDepartmentId(user.getDepartmentId());
        dto.setDepartmentName(user.getDepartmentName());
        dto.setEmail(user.getEmail());
        return dto;
    }
}
