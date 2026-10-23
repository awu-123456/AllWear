package com.bit.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bit.common.Result;
import com.bit.dto.request.AuthRequest;
import com.bit.dto.request.SendVerificationCodeRequest;
import com.bit.dto.response.AuthResponse;
import com.bit.entity.User;
import com.bit.log.ApiLog;
import com.bit.mapper.UserMapper;
import com.bit.service.UserService;
import com.bit.util.EmailService;
import com.bit.util.JwtUtil;
import com.bit.util.VerificationCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

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

    private final BCryptPasswordEncoder bCryptPasswordEncoder = new BCryptPasswordEncoder();

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
            String code = request.getVerificationCode();
            if(code == null || code.isBlank()) {
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
        } else {
            if(user == null) {
                user = new User();
                user.setUserName(account);
                if(request.getPassword() == null || request.getPassword().isBlank()) {
                    throw new RuntimeException("密码不能为空");
                }
                user.setPasswordHash(bCryptPasswordEncoder.encode(request.getPassword()));
                userMapper.insert(user);
            } else {
                if(request.getPassword() == null || !bCryptPasswordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                    throw new RuntimeException("用户名或密码错误");
                }
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

    @Override
    public boolean logout(String authorization) {
        String token = jwtUtil.parseToken(authorization);
        return jwtUtil.removeToken(token);
    }
}
