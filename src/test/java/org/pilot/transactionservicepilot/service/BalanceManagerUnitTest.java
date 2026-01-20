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

@SuppressWarnings({"rawtypes","unchecked"})
public class BalanceManagerUnitTest {

    RedisTemplate<String, String> redisTemplate;
    BalanceManager balanceManager;
    HashOperations<String, Object, Object> mockHashOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        mockHashOps = mock(HashOperations.class);
        doReturn(mockHashOps).when(redisTemplate).opsForHash();
        balanceManager = new BalanceManager(redisTemplate);
        // reserveScript is normally loaded in @PostConstruct; inject a mock so redisTemplate.execute stubbing matches
        try {
            java.lang.reflect.Field f = BalanceManager.class.getDeclaredField("reserveScript");
            f.setAccessible(true);
            f.set(balanceManager, mock(org.springframework.data.redis.core.script.RedisScript.class));
        } catch (Exception ignored) {
        }
    }

    @Test
    void populateBalance_puts_cents_and_currency() {
        balanceManager.populateBalance(1L, new BigDecimal("12.34"), new BigDecimal("5.67"), "USD");
        // balance cents = 1234, available cents = 567
        verify(mockHashOps).put("balance:1", "balance", String.valueOf(1234L));
        verify(mockHashOps).put("balance:1", "available", String.valueOf(567L));
        verify(mockHashOps).put("balance:1", "currency", "USD");
    }

    @Test
    void getBalance_and_getAvailable_parses_cents_correctly() {
        Map<Object,Object> map = new HashMap<>();
        map.put("balance", "1234");
        map.put("available", "567");
        when(mockHashOps.entries("balance:1")).thenReturn(map);

        BigDecimal b = balanceManager.getBalance(1L);
        BigDecimal a = balanceManager.getAvailable(1L);

        assertThat(b).isEqualByComparingTo(new BigDecimal("12.34"));
        assertThat(a).isEqualByComparingTo(new BigDecimal("5.67"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reserve_maps_script_result_to_enum() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("OK");
        BalanceManager.ReserveResult r1 = balanceManager.reserve(1L, new BigDecimal("1.00"), "tx1");
        assertThat(r1).isEqualTo(BalanceManager.ReserveResult.OK);

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("NO_ACCOUNT");
        BalanceManager.ReserveResult r2 = balanceManager.reserve(1L, new BigDecimal("1.00"), "tx2");
        assertThat(r2).isEqualTo(BalanceManager.ReserveResult.NO_ACCOUNT);

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("INSUFFICIENT_FUNDS");
        BalanceManager.ReserveResult r3 = balanceManager.reserve(1L, new BigDecimal("1.00"), "tx3");
        assertThat(r3).isEqualTo(BalanceManager.ReserveResult.INSUFFICIENT_FUNDS);

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);
        BalanceManager.ReserveResult r4 = balanceManager.reserve(1L, new BigDecimal("1.00"), "tx4");
        assertThat(r4).isEqualTo(BalanceManager.ReserveResult.ERROR);

        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(new RuntimeException("boom"));
        BalanceManager.ReserveResult r5 = balanceManager.reserve(1L, new BigDecimal("1.00"), "tx5");
        assertThat(r5).isEqualTo(BalanceManager.ReserveResult.ERROR);
    }

    @Test
    void commit_and_rollback_call_increment_and_delete() {
        // commit should decrement balance by cents and delete reservation
        balanceManager.commit(2L, new BigDecimal("1.23"), "tx-c");
        verify(mockHashOps).increment("balance:2", "balance", -123L);
        verify(redisTemplate).delete("reservation:tx-c");

        // rollback should increment available by cents and delete reservation
        balanceManager.rollback(3L, new BigDecimal("2.50"), "tx-r");
        verify(mockHashOps).increment("balance:3", "available", 250L);
        verify(redisTemplate).delete("reservation:tx-r");
    }
}
