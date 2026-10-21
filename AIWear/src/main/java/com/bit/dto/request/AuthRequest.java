package com.bit.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AuthRequest {

    @NotBlank(message = "账号不能为空")
    private String account;

    private String password;

    private String verificationCode;
}
