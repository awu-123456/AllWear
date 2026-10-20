package com.bit.service.impl;

import com.bit.dto.request.SendVerificationCodeRequest;
import com.bit.service.UserService;
import com.bit.util.EmailService;
import com.bit.util.VerificationCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private EmailService emailService;

    @Autowired
    private VerificationCodeService verificationCodeService;

    @Override
    public boolean sendVerificationCode(SendVerificationCodeRequest request) {
        String email = request.getEmail();
        if (verificationCodeService.hasCode(email)) {
            throw new RuntimeException("验证码尚未过期，请重新发送");
        }
        String code = verificationCodeService.generateCode();
        verificationCodeService.saveCode(email, code);
        return emailService.sendEmail(email, code);
    }
}
