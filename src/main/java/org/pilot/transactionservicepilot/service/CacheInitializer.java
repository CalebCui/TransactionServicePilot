package org.pilot.transactionservicepilot.service;

import org.pilot.transactionservicepilot.entity.Account;
import org.pilot.transactionservicepilot.repository.AccountRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CacheInitializer implements CommandLineRunner {

    private final AccountRepository accountRepository;
    private final RedisTemplate<String, String> redisTemplate;

    public CacheInitializer(AccountRepository accountRepository, RedisTemplate<String, String> redisTemplate) {
        this.accountRepository = accountRepository;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void run(String... args) throws Exception {
        // load existing accounts into Redis hash (balance & available)
        List<Account> accounts = accountRepository.findAll();
        for (Account a : accounts) {
            String key = "balance:" + a.getId();
            redisTemplate.opsForHash().put(key, "balance", a.getBalance().toPlainString());
            redisTemplate.opsForHash().put(key, "available", a.getAvailableBalance().toPlainString());
            redisTemplate.opsForHash().put(key, "currency", a.getCurrency());
        }
    }
}
