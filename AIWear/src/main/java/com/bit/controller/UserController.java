package com.bit.controller;

import com.bit.common.Result;
import com.bit.dto.request.AuthRequest;
import com.bit.dto.request.SendVerificationCodeRequest;
import com.bit.dto.response.AuthResponse;
import com.bit.dto.response.SendVerificationCodeResponse;
import com.bit.log.ApiLog;
import com.bit.service.UserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    @ApiLog
    @PostMapping("/send-code")
    public Result<SendVerificationCodeResponse> sendVerification(@RequestBody @Valid SendVerificationCodeRequest request) {
        boolean success = userService.sendVerificationCode(request);
        if(success) {
            return Result.success("验证码发送成功", SendVerificationCodeResponse.builder().sendTo("***").expireTime(300).build());
        } else {
            return Result.serverError("发送验证码失败，请稍后重试");
        }
    }

    @ApiLog
    @PostMapping("/auth")
    public Result<AuthResponse> auth(@RequestBody @Valid AuthRequest request) {
        return Result.success("操作成功", userService.auth(request));
    }
}
