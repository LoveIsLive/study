package com.kwang.study.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private final StringRedisTemplate redisTemplate;
    public static final String LOGIN_ATTEMPT_PREFIX = "login:attempts:";

    public LoginAttemptService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 当登录失败时调用此方法
     * @param username 用户名
     */
    public void loginFailed(String username) {
        String key = buildKey(username);
        Long attempts = redisTemplate.opsForValue().increment(key);

        // 如果是第一次失败，设置key的过期时间为当天午夜
        if (attempts != null && attempts == 1) {
            long secondsUntilMidnight = Duration.between(LocalDateTime.now(),
                    LocalDateTime.now().with(LocalTime.MAX)).getSeconds();
            redisTemplate.expire(key, secondsUntilMidnight, TimeUnit.SECONDS);
        }
    }

    /**
     * 当登录成功时调用此方法
     * @param username 用户名
     */
    public void loginSucceeded(String username) {
        String key = buildKey(username);
        redisTemplate.delete(key);
    }

    /**
     * 检查用户是否已被锁定
     * @param username 用户名
     * @return 如果尝试次数超过限制，则返回 true
     */
    public boolean isBlocked(String username) {
        String key = buildKey(username);
        String attemptsStr = redisTemplate.opsForValue().get(key);
        if (attemptsStr == null) {
            return false;
        }
        int attempts = Integer.parseInt(attemptsStr);
        return attempts >= MAX_ATTEMPTS;
    }

    private String buildKey(String username) {
        return LOGIN_ATTEMPT_PREFIX + username;
    }
}
