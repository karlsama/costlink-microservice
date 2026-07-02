package com.costlink.auth.dto;

import com.costlink.common.feign.AuthClient;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResponse {
    private String accessToken;
    private String refreshToken;
    private Long expiresIn;
    @Builder.Default
    private String tokenType = "Bearer";
    private AuthClient.UserInfoDTO userInfo;
}
