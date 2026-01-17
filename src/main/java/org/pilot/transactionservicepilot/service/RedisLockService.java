package org.pilot.transactionservicepilot.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class RedisLockService {

    private final RedisTemplate<String, String> redisTemplate;

    public RedisLockService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String tryLock(String key, long ttlMillis) {
        String lockKey = "lock:" + key;
        String token = UUID.randomUUID().toString();
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(lockKey, token, Duration.ofMillis(ttlMillis));
        if (Boolean.TRUE.equals(ok)) {
            return token;
        }
        return null;
    }

    public boolean unlock(String key, String token) {
        String lockKey = "lock:" + key;
        String value = redisTemplate.opsForValue().get(lockKey);
        if (token != null && token.equals(value)) {
            return redisTemplate.delete(lockKey);
        }
        return false;
    }
}
