package com.bit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bit.dto.request.AuthRequest;
import com.bit.dto.request.SendVerificationCodeRequest;
import com.bit.dto.response.AuthResponse;
import com.bit.entity.User;
import com.bit.mapper.UserMapper;
import com.bit.service.UserService;
import com.bit.util.EmailService;
import com.bit.util.JwtUtil;
import com.bit.util.VerificationCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private EmailService emailService;

    @Autowired
    private VerificationCodeService verificationCodeService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    JwtUtil jwtUtil;

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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthResponse auth(AuthRequest request) {
        String account =  request.getAccount();
        boolean isEmail = account.contains("@");
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUserName, account);
        User user = userMapper.selectOne(queryWrapper);
        if(isEmail) {
            String code = verificationCodeService.generateCode();
            if(code == null || code.isEmpty()) {
                throw new RuntimeException("验证码不能为空");
            }
            if(!verificationCodeService.verifyCode(account, code)) {
                throw new RuntimeException("验证码不存在或者已过期");
            }
            if(user == null) {
                user = new User();
                user.setUserName(account);
                user.setEmail(account);
                userMapper.insert(user);
                log.info("新用户注册成功，邮箱:{}",account);
            }
        }
        return createAuthResponse(user);
    }

    private AuthResponse createAuthResponse(User user) {
        AuthResponse authResponse = new AuthResponse();
        authResponse.setUserId(user.getId());
        authResponse.setUserName(user.getUserName());
        authResponse.setEmail(user.getEmail());
        authResponse.setToken(jwtUtil.generateToken(user));
        return authResponse;
    }
}
