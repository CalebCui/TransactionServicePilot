package org.pilot.transactionservicepilot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class BalanceManager {

    private final RedisTemplate<String, String> redisTemplate;

    @Value("${app.redis.reservation-ttl-seconds:30}")
    private int reservationTtlSeconds;

    private RedisScript<String> reserveScript;

    public BalanceManager(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void loadScripts() throws Exception {
        ClassPathResource res = new ClassPathResource("redis/scripts/reserve_balance.lua");
        byte[] bytes = FileCopyUtils.copyToByteArray(res.getInputStream());
        String script = new String(bytes, StandardCharsets.UTF_8);
        this.reserveScript = new DefaultRedisScript<>(script, String.class);
    }

    private String balanceKey(Long accountId) {
        return "balance:" + accountId;
    }

    private String reservationKey(String txId) {
        return "reservation:" + txId;
    }

    public enum ReserveResult {
        OK, NO_ACCOUNT, INSUFFICIENT_FUNDS, ERROR
    }

    // helper: convert BigDecimal to integer cents (long)
    private long toCents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).multiply(new BigDecimal(100)).longValueExact();
    }

    private BigDecimal fromCents(Object centsObj) {
        if (centsObj == null) return null;
        long cents;
        if (centsObj instanceof Number) {
            cents = ((Number) centsObj).longValue();
        } else {
            try {
                cents = Long.parseLong(centsObj.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new BigDecimal(cents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    public ReserveResult reserve(Long accountId, BigDecimal amount, String txId) {
        String bKey = balanceKey(accountId);
        String rKey = reservationKey(txId);
        List<String> keys = List.of(bKey, rKey);
        long cents = toCents(amount);
        List<String> args = List.of(String.valueOf(cents), txId, String.valueOf(reservationTtlSeconds));
        try {
            String res = redisTemplate.execute(reserveScript, keys, (Object[]) args.toArray(new String[0]));
            if (res == null) return ReserveResult.ERROR;
            if ("OK".equalsIgnoreCase(res)) return ReserveResult.OK;
            if (res.contains("NO_ACCOUNT")) return ReserveResult.NO_ACCOUNT;
            if (res.contains("INSUFFICIENT_FUNDS")) return ReserveResult.INSUFFICIENT_FUNDS;
            return ReserveResult.ERROR;
        } catch (Exception e) {
            return ReserveResult.ERROR;
        }
    }

    public void commit(Long accountId, BigDecimal amount, String txId) {
        String bKey = balanceKey(accountId);
        long cents = toCents(amount);
        // decrement final balance (balance is stored in cents as integer string)
        redisTemplate.opsForHash().increment(bKey, "balance", -cents);
        // remove reservation
        redisTemplate.delete(reservationKey(txId));
    }

    public void rollback(Long accountId, BigDecimal amount, String txId) {
        String bKey = balanceKey(accountId);
        long cents = toCents(amount);
        // return available back (increment available by cents)
        redisTemplate.opsForHash().increment(bKey, "available", cents);
        redisTemplate.delete(reservationKey(txId));
    }

    public BigDecimal getBalance(Long accountId) {
        String bKey = balanceKey(accountId);
        Map<Object, Object> map = redisTemplate.opsForHash().entries(bKey);
        if (map == null || map.isEmpty()) return null;
        Object bal = map.get("balance");
        return fromCents(bal);
    }

    // helper to get available balance
    public BigDecimal getAvailable(Long accountId) {
        String bKey = balanceKey(accountId);
        Map<Object, Object> map = redisTemplate.opsForHash().entries(bKey);
        if (map == null || map.isEmpty()) return null;
        Object av = map.get("available");
        return fromCents(av);
    }

    // New helper to populate or update the Redis balance hash from DB values
    public void populateBalance(Long accountId, BigDecimal balance, BigDecimal available, String currency) {
        String bKey = balanceKey(accountId);
        if (balance != null) {
            long bc = toCents(balance);
            redisTemplate.opsForHash().put(bKey, "balance", String.valueOf(bc));
        } else {
            redisTemplate.opsForHash().delete(bKey, "balance");
        }
        if (available != null) {
            long ac = toCents(available);
            redisTemplate.opsForHash().put(bKey, "available", String.valueOf(ac));
        } else {
            redisTemplate.opsForHash().delete(bKey, "available");
        }
        if (currency != null) {
            redisTemplate.opsForHash().put(bKey, "currency", currency);
        }
    }
}
