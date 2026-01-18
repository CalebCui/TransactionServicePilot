package org.pilot.transactionservicepilot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pilot.transactionservicepilot.entity.TransactionRecord;
import org.pilot.transactionservicepilot.repository.TransactionRepository;
import org.pilot.transactionservicepilot.repository.AccountRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class TransactionServiceReprocessFlowTest {

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
    void reprocessPending_accountNotFound_retriesAndThenPermanentFail() {
        TransactionRecord rec = new TransactionRecord();
        rec.setId(1L);
        rec.setTxId("tx-1");
        rec.setAccountId(42L);
        rec.setAmount(new BigDecimal("10.00"));
        rec.setStatus("PENDING");
        rec.setRetryCount(0);

        // make findRetryable return our mutable record object each call
        when(transactionRepository.findRetryable(anyList(), any(Instant.class))).thenAnswer(invocation -> List.of(rec));
        // account is missing
        when(accountRepository.findById(rec.getAccountId())).thenReturn(Optional.empty());

        // first reprocess -> retryCount = 1, nextAttemptAt set
        transactionService.reprocessPending();
        System.out.println("after1 retry=" + rec.getRetryCount() + " status=" + rec.getStatus() + " nextAttemptAt=" + rec.getNextAttemptAt());
        assertEquals(1, rec.getRetryCount().intValue());
        assertNotNull(rec.getNextAttemptAt());
        assertEquals("PENDING", rec.getStatus());

        // force nextAttemptAt to past to simulate backoff expiration
        rec.setNextAttemptAt(Instant.now().minusSeconds(1));

        // second reprocess -> retryCount = 2
        transactionService.reprocessPending();
        System.out.println("after2 retry=" + rec.getRetryCount() + " status=" + rec.getStatus() + " nextAttemptAt=" + rec.getNextAttemptAt());
        assertEquals(2, rec.getRetryCount().intValue());
        assertNotNull(rec.getNextAttemptAt());
        assertEquals("PENDING", rec.getStatus());

        // force nextAttemptAt to past again
        rec.setNextAttemptAt(Instant.now().minusSeconds(1));

        // third reprocess -> should mark as FAILED and not schedule further attempts
        transactionService.reprocessPending();
        System.out.println("after3 retry=" + rec.getRetryCount() + " status=" + rec.getStatus() + " nextAttemptAt=" + rec.getNextAttemptAt());
        assertEquals(3, rec.getRetryCount().intValue());
        assertEquals("FAILED", rec.getStatus());
        assertNull(rec.getNextAttemptAt());

        // verify repository.save was called at least 3 times
        verify(transactionRepository, atLeast(3)).save(any(TransactionRecord.class));
    }
}
