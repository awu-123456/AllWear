package com.bit.service;

import com.bit.dto.request.SendVerificationCodeRequest;

public interface UserService {
    boolean sendVerificationCode(SendVerificationCodeRequest request);
}
