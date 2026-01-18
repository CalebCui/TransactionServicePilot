package org.pilot.transactionservicepilot.service;

import org.junit.jupiter.api.Test;
import org.pilot.transactionservicepilot.dto.TransactionRequest;
import org.pilot.transactionservicepilot.entity.Account;
import org.pilot.transactionservicepilot.entity.TransactionRecord;
import org.pilot.transactionservicepilot.repository.AccountRepository;
import org.pilot.transactionservicepilot.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {"app.sync.base-backoff-seconds=1","app.sync.max-retries=3"})
public class TransactionServiceOptimisticLockTest {

    @Autowired
    TransactionService transactionService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    TransactionRepository transactionRepository;

    @Test
    void optimisticLockDuringDbFallbackTriggersRetryAndBackoff() {
        Account a = new Account();
        a.setAccountNumber("ol-acct-" + Instant.now().toEpochMilli());
        a.setCurrency("CNY");
        a.setBalance(new BigDecimal("100.00"));
        a.setAvailableBalance(new BigDecimal("100.00"));
        Account saved = accountRepository.save(a);

        // create a pending transaction
        TransactionRecord rec = new TransactionRecord();
        rec.setTxId("ol-tx-" + Instant.now().toEpochMilli());
        rec.setAccountId(saved.getId());
        rec.setType("DEBIT");
        rec.setAmount(new BigDecimal("10.00"));
        rec.setCurrency("CNY");
        rec.setStatus("PENDING");
        rec.setRetryCount(0);
        transactionRepository.save(rec);

        // Simulate another concurrent update that increments version: update balance via repository
        saved.setBalance(saved.getBalance().subtract(new BigDecimal("1.00")));
        accountRepository.save(saved); // this will increment version

        // Now call reprocessPending which will load the transaction, load account (stale), and then attempt to save the account modifications -> optimistic lock should occur
        transactionService.reprocessPending();

        TransactionRecord after = transactionRepository.findByTxId(rec.getTxId()).orElseThrow();
        assertTrue(after.getRetryCount() >= 1, "Retry should have been incremented after optimistic lock failure");
        // If retries < max, nextAttemptAt should be set
        if (after.getRetryCount() < 3) {
            assertNotNull(after.getNextAttemptAt());
        }
    }
}
