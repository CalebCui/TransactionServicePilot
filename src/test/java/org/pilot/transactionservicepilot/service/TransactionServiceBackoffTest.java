package org.pilot.transactionservicepilot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pilot.transactionservicepilot.entity.TransactionRecord;
import org.pilot.transactionservicepilot.repository.TransactionRepository;
import org.pilot.transactionservicepilot.repository.AccountRepository;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionServiceBackoffTest {

    private BalanceManager balanceManager;
    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        balanceManager = mock(BalanceManager.class);
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        transactionService = new TransactionService(balanceManager, transactionRepository, accountRepository);
    }

    @Test
    void calculateNextAttempt_exponentialBackoff() {
        Instant now = Instant.now();
        Instant n1 = transactionService.calculateNextAttempt(now, 1);
        Instant n2 = transactionService.calculateNextAttempt(now, 2);
        Instant n3 = transactionService.calculateNextAttempt(now, 3);

        long d1 = n1.getEpochSecond() - now.getEpochSecond();
        long d2 = n2.getEpochSecond() - now.getEpochSecond();
        long d3 = n3.getEpochSecond() - now.getEpochSecond();

        assertEquals(5L, d1);
        assertEquals(10L, d2);
        assertEquals(20L, d3);
    }

}
