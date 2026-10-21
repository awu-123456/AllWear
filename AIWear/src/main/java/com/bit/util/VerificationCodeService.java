package com.bit.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Random;

@Slf4j
@Service
public class VerificationCodeService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String Code_KEY_PREFIX = "verification:code";

    private static final int TIMEOUT_MINUTES = 5;

    public String generateCode() {
        Random random = new Random();
        int code = 100000 + random.nextInt(99999);
        return String.valueOf(code);
    }

    public void saveCode(String email, String code) {
        String key = Code_KEY_PREFIX + email;
        redisTemplate.opsForValue().set(key, code, TIMEOUT_MINUTES);
    }

    public boolean hasCode(String email) {
        String key = Code_KEY_PREFIX + email;
        return redisTemplate.hasKey(key);
    }

    public boolean verifyCode(String email, String code) {
        String key = Code_KEY_PREFIX + email;
        if (redisTemplate.hasKey(key)) {
            return code.equals(redisTemplate.opsForValue().get(key));
        } else {
            log.warn("验证码不存在或已过期，邮箱{}",email);
            return false;
        }
    }

}
