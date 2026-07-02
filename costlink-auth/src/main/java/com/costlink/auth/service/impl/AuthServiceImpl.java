package com.costlink.auth.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.costlink.auth.dto.LoginRequest;
import com.costlink.auth.dto.LoginResponse;
import com.costlink.auth.dto.RefreshRequest;
import com.costlink.auth.entity.User;
import com.costlink.auth.mapper.UserMapper;
import com.costlink.auth.service.AuthService;
import com.costlink.common.dto.Result;
import com.costlink.common.exception.BusinessException;
import com.costlink.common.exception.ErrorCode;
import com.costlink.common.feign.AuthClient;
import com.costlink.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    public Result<LoginResponse> login(LoginRequest request) {
        User user = userMapper.selectOne(
            new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername())
        );
        if (user == null) {
            throw new BusinessException(ErrorCode.AUTH_LOGIN_FAILED);
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.AUTH_LOGIN_FAILED);
        }
        log.info("登录成功, userId={}, username={}", user.getId(), user.getUsername());
        return Result.ok(buildLoginResponse(user));
    }

    @Override
    public Result<LoginResponse> refresh(RefreshRequest request) {
        Claims claims;
        try {
            claims = jwtUtil.parseToken(request.getRefreshToken());
        } catch (JwtException e) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_EXPIRED);
        }
        Long userId = Long.valueOf(claims.getSubject());

        String storedToken = redisTemplate.opsForValue().get("auth:refresh:" + userId);
        if (StrUtil.isBlank(storedToken) || !storedToken.equals(request.getRefreshToken())) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_EXPIRED);
        }

        User user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }

        log.info("Token刷新成功, userId={}", userId);
        return Result.ok(buildLoginResponse(user));
    }

    @Override
    public void logout(Long userId) {
        if (userId != null) {
            redisTemplate.delete("auth:refresh:" + userId);
        }
        log.info("登出成功, userId={}", userId);
    }

    private LoginResponse buildLoginResponse(User user) {
        String accessToken = jwtUtil.generateAccessToken(
            user.getId(), user.getRole(), user.getDepartmentId()
        );
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());

        redisTemplate.opsForValue().set(
            "auth:refresh:" + user.getId(),
            refreshToken,
            jwtUtil.getRefreshTokenTtl(),
            TimeUnit.DAYS
        );

        return LoginResponse.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .expiresIn(jwtUtil.getAccessTokenTtl())
            .userInfo(toDTO(user))
            .build();
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
