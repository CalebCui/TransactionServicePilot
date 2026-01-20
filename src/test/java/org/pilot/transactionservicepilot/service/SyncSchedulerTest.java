package org.pilot.transactionservicepilot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pilot.transactionservicepilot.entity.Account;
import org.pilot.transactionservicepilot.repository.AccountRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.*;

public class SyncSchedulerTest {

    AccountRepository accountRepository;
    BalanceManager balanceManager;
    TransactionService transactionService;
    MeterRegistry meterRegistry;
    Counter counter;
    SyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        balanceManager = mock(BalanceManager.class);
        transactionService = mock(TransactionService.class);
        meterRegistry = mock(MeterRegistry.class);
        // ensure meterRegistry.counter(...) returns a non-null Counter to avoid NPE in SyncScheduler
        counter = mock(Counter.class);
        when(meterRegistry.counter(anyString())).thenReturn(counter);
        scheduler = new SyncScheduler(accountRepository, balanceManager, transactionService, meterRegistry);
    }

    @Test
    void reconcile_populates_missing_balance_and_calls_reprocess() {
        Account a1 = new Account(); a1.setId(1L); a1.setBalance(new BigDecimal("10.00")); a1.setAvailableBalance(new BigDecimal("10.00")); a1.setCurrency("USD");
        when(accountRepository.findAll()).thenReturn(List.of(a1));
        when(balanceManager.getBalance(1L)).thenReturn(null);

        // transactionService.reprocessPending should be invoked without throwing
        scheduler.reconcile();

        verify(balanceManager).populateBalance(eq(1L), any(), any(), eq("USD"));
        verify(transactionService).reprocessPending();
    }

    @Test
    void reconcile_handles_reprocess_exceptions_and_respects_retries() {
        when(accountRepository.findAll()).thenReturn(List.of());
        doThrow(new RuntimeException("boom")).when(transactionService).reprocessPending();
        // This will cause the scheduler to attempt up to maxRetries and then stop; we run it
        scheduler.reconcile();
        verify(transactionService, atLeastOnce()).reprocessPending();
    }
}
