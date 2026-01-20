package org.pilot.transactionservicepilot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class RedisLockServiceTest {

    RedisTemplate<String, String> redisTemplate;
    ValueOperations<String, String> valueOps;
    RedisLockService lockService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lockService = new RedisLockService(redisTemplate);
    }

    @Test
    void tryLock_returnsToken_when_setIfAbsent_true() {
        when(redisTemplate.opsForValue().setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        String token = lockService.tryLock("key1", 1000);
        assertThat(token).isNotNull();
    }

    @Test
    void tryLock_returnsNull_when_setIfAbsent_false() {
        when(redisTemplate.opsForValue().setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        String token = lockService.tryLock("key1", 1000);
        assertThat(token).isNull();
    }

    @Test
    void unlock_returnsTrue_when_token_matches_and_delete_true() {
        when(redisTemplate.opsForValue().get("lock:key2")).thenReturn("tok");
        when(redisTemplate.delete("lock:key2")).thenReturn(true);
        boolean ok = lockService.unlock("key2", "tok");
        assertThat(ok).isTrue();
    }

    @Test
    void unlock_returnsFalse_when_token_mismatch_or_null() {
        when(redisTemplate.opsForValue().get("lock:key2")).thenReturn("other");
        boolean ok = lockService.unlock("key2", "tok");
        assertThat(ok).isFalse();

        boolean ok2 = lockService.unlock("key2", null);
        assertThat(ok2).isFalse();
    }
}
