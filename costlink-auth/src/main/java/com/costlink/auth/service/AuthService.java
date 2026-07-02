package com.costlink.auth.service;

import com.costlink.auth.dto.LoginRequest;
import com.costlink.auth.dto.LoginResponse;
import com.costlink.auth.dto.RefreshRequest;
import com.costlink.common.dto.Result;

public interface AuthService {
    Result<LoginResponse> login(LoginRequest request);
    Result<LoginResponse> refresh(RefreshRequest request);
    void logout(Long userId);
}
