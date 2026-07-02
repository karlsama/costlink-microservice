package com.costlink.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshRequest {
    @NotBlank(message = "RefreshToken不能为空")
    private String refreshToken;
}
