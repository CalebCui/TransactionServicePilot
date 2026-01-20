package org.pilot.transactionservicepilot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
public class BalanceManagerTest {

    RedisTemplate<String, String> redisTemplate;
    HashOperations<String, Object, Object> hashOps;
    BalanceManager balanceManager;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        balanceManager = new BalanceManager(redisTemplate);
        // avoid loading script from classpath in unit tests
        // instead set reserveScript via reflection if needed; we will mock execute
        try {
            java.lang.reflect.Field f = BalanceManager.class.getDeclaredField("reserveScript");
            f.setAccessible(true);
            f.set(balanceManager, mock(RedisScript.class));
        } catch (Exception ignored) {
        }
    }

    @Test
    void reserve_returnsOK_when_redis_executes_OK() {
        when(redisTemplate.execute((RedisScript<String>) any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("OK");
        BalanceManager.ReserveResult res = balanceManager.reserve(1L, new BigDecimal("10.00"), "tx1");
        assertThat(res).isEqualTo(BalanceManager.ReserveResult.OK);
    }

    @Test
    void reserve_handles_no_account_and_insufficient_cases() {
        when(redisTemplate.execute((RedisScript<String>) any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("NO_ACCOUNT");
        assertThat(balanceManager.reserve(1L, new BigDecimal("10.00"), "tx1")).isEqualTo(BalanceManager.ReserveResult.NO_ACCOUNT);

        when(redisTemplate.execute((RedisScript<String>) any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("INSUFFICIENT_FUNDS");
        assertThat(balanceManager.reserve(1L, new BigDecimal("10.00"), "tx1")).isEqualTo(BalanceManager.ReserveResult.INSUFFICIENT_FUNDS);
    }

    @Test
    void reserve_returnsError_on_exception_or_unknown() {
        when(redisTemplate.execute((RedisScript<String>) any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(new RuntimeException("boom"));
        assertThat(balanceManager.reserve(1L, new BigDecimal("10.00"), "tx1")).isEqualTo(BalanceManager.ReserveResult.ERROR);

        when(redisTemplate.execute((RedisScript<String>) any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("SOMETHING_ELSE");
        assertThat(balanceManager.reserve(1L, new BigDecimal("10.00"), "tx1")).isEqualTo(BalanceManager.ReserveResult.ERROR);
    }

    @Test
    void commit_and_rollback_and_populate_and_getBalance_available_work() {
        // populateBalance should call hash put
        balanceManager.populateBalance(2L, new BigDecimal("12.34"), new BigDecimal("5.00"), "USD");
        verify(hashOps, times(3)).put(eq("balance:2"), any(), any());

        // commit should call increment with negative cents and delete reservation
        balanceManager.commit(2L, new BigDecimal("1.00"), "tx2");
        verify(hashOps).increment("balance:2", "balance", -100L);
        verify(redisTemplate).delete("reservation:tx2");

        // rollback should increment available and delete
        balanceManager.rollback(2L, new BigDecimal("2.00"), "tx3");
        verify(hashOps).increment("balance:2", "available", 200L);
        verify(redisTemplate).delete("reservation:tx3");

        // getBalance/getAvailable will call opsForHash().entries
        Map<Object, Object> map = new HashMap<>();
        map.put("balance", "1234");
        map.put("available", "500");
        when(hashOps.entries("balance:2")).thenReturn(map);
        assertThat(balanceManager.getBalance(2L)).isEqualTo(new BigDecimal("12.34"));
        assertThat(balanceManager.getAvailable(2L)).isEqualTo(new BigDecimal("5.00"));
    }
}
