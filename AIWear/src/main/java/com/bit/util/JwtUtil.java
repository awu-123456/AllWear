package com.bit.util;

import com.bit.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class JwtUtil {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expiration;

    private static final String USER_TOKEN_KEY_PREFIX = "jwt:user:";

    public String generateToken(User user) {
        String userKey = USER_TOKEN_KEY_PREFIX + user.getId();
        if(redisTemplate.hasKey(userKey)){
            return redisTemplate.opsForValue().get(userKey);
        }
        Map<String,Object> map = new HashMap<>();
        map.put("userId", user.getId());
        map.put("userName", user.getUserName());
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration * 1000 * 60 * 60 * 2);
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .setClaims(map)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(key)
                .compact();
        redisTemplate.opsForValue()
                .set(
                        userKey,
                        token,
                        expiration,
                        TimeUnit.HOURS
                );
        return token;
    }

    public String parseToken(String authorization) {
        if(authorization == null || authorization.isBlank()) {
            throw new RuntimeException("缺少请求头令牌！");
        }
        return authorization.substring(7);
    }

    private Claims getClaims(String token) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public boolean removeToken(String token) {
        if(token == null || token.isBlank()) {
            return true;
        }
        Claims claims = getClaims(token);
        Long userId = Long.parseLong(claims.get("userId").toString());
        String userKey = USER_TOKEN_KEY_PREFIX + userId;
        if(redisTemplate.hasKey(userKey)) {
            return redisTemplate.delete(userKey);
        }
        return false;
    }

    public Long getUserId(String token) {
        Claims claims = getClaims(token);
        Long userId = Long.parseLong(claims.get("userId").toString());
        String userKey = USER_TOKEN_KEY_PREFIX + userId;
        String cachedToken = redisTemplate.opsForValue().get(userKey);
        if (cachedToken == null || !cachedToken.equals(token)) {
            throw new RuntimeException("令牌无效或已过期！");
        }
        return userId;
    }

}
