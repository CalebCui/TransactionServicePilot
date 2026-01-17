package org.pilot.transactionservicepilot.integration;

import org.pilot.transactionservicepilot.service.BalanceManager;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

@Component
@Primary
public class InMemoryBalanceManager extends BalanceManager {

    private final Map<String, Map<String, String>> store = new HashMap<>();

    public InMemoryBalanceManager() {
        super(null);
    }

    private long toCents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).multiply(new BigDecimal(100)).longValueExact();
    }

    private BigDecimal fromCents(String s) {
        if (s == null) return null;
        long cents = Long.parseLong(s);
        return new BigDecimal(cents).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public ReserveResult reserve(Long accountId, BigDecimal amount, String txId) {
        String key = "balance:" + accountId;
        Map<String, String> map = store.computeIfAbsent(key, k -> new HashMap<>());
        String balanceStr = map.get("available");
        if (balanceStr == null) return ReserveResult.NO_ACCOUNT;
        BigDecimal available = fromCents(balanceStr);
        if (available.compareTo(amount) < 0) return ReserveResult.INSUFFICIENT_FUNDS;
        long newAvail = toCents(available.subtract(amount));
        map.put("available", String.valueOf(newAvail));
        map.put("reservation:" + txId, String.valueOf(toCents(amount)));
        return ReserveResult.OK;
    }

    @Override
    public void commit(Long accountId, BigDecimal amount, String txId) {
        String key = "balance:" + accountId;
        Map<String, String> map = store.computeIfAbsent(key, k -> new HashMap<>());
        String bal = map.get("balance");
        BigDecimal b = bal == null ? BigDecimal.ZERO : fromCents(bal);
        b = b.subtract(amount);
        map.put("balance", String.valueOf(toCents(b)));
        map.remove("reservation:" + txId);
    }

    @Override
    public void rollback(Long accountId, BigDecimal amount, String txId) {
        String key = "balance:" + accountId;
        Map<String, String> map = store.computeIfAbsent(key, k -> new HashMap<>());
        String avail = map.get("available");
        BigDecimal a = avail == null ? BigDecimal.ZERO : fromCents(avail);
        a = a.add(amount);
        map.put("available", String.valueOf(toCents(a)));
        map.remove("reservation:" + txId);
    }

    @Override
    public BigDecimal getBalance(Long accountId) {
        String key = "balance:" + accountId;
        Map<String, String> map = store.get(key);
        if (map == null) return null;
        String bal = map.get("balance");
        return bal == null ? null : fromCents(bal);
    }

    @Override
    public void populateBalance(Long accountId, BigDecimal balance, BigDecimal available, String currency) {
        String key = "balance:" + accountId;
        Map<String, String> map = store.computeIfAbsent(key, k -> new HashMap<>());
        if (balance != null) map.put("balance", String.valueOf(toCents(balance)));
        if (available != null) map.put("available", String.valueOf(toCents(available)));
        if (currency != null) map.put("currency", currency);
    }
}
