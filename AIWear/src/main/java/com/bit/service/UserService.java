package com.bit.service;

import com.bit.dto.request.AuthRequest;
import com.bit.dto.request.SendVerificationCodeRequest;
import com.bit.dto.response.AuthResponse;
import jakarta.validation.Valid;

public interface UserService {
    boolean sendVerificationCode(SendVerificationCodeRequest request);

    AuthResponse auth(@Valid AuthRequest request);
}
