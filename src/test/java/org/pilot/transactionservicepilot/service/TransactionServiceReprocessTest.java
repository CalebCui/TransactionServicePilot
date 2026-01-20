package org.pilot.transactionservicepilot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pilot.transactionservicepilot.entity.TransactionRecord;
import org.pilot.transactionservicepilot.entity.Account;
import org.pilot.transactionservicepilot.repository.TransactionRepository;
import org.pilot.transactionservicepilot.repository.AccountRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

public class TransactionServiceReprocessTest {

    TransactionRepository transactionRepository;
    AccountRepository accountRepository;
    BalanceManager balanceManager;
    TransactionService svc;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        balanceManager = mock(BalanceManager.class);
        svc = new TransactionService(balanceManager, transactionRepository, accountRepository);
    }

    @Test
    void reprocessPending_handles_no_account_and_permanent_failure() {
        TransactionRecord rec = new TransactionRecord();
        rec.setTxId("r1");
        rec.setAccountId(99L);
        rec.setAmount(new BigDecimal("1.00"));
        rec.setStatus("PENDING");
        rec.setRetryCount(0);
        rec.setNextAttemptAt(null);

        when(transactionRepository.findRetryable(any(), any())).thenReturn(List.of(rec));
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        svc.reprocessPending();

        verify(transactionRepository).save(argThat(r -> r.getRetryCount() != null && r.getRetryCount() > 0));
    }

    @Test
    void reprocessPending_handles_reserve_ok_and_db_apply_success() {
        TransactionRecord rec = new TransactionRecord();
        rec.setTxId("r2");
        rec.setAccountId(1L);
        rec.setAmount(new BigDecimal("1.00"));
        rec.setType("DEBIT");
        rec.setStatus("PENDING");
        rec.setRetryCount(0);

        Account a = new Account(); a.setId(1L); a.setBalance(new BigDecimal("10.00")); a.setAvailableBalance(new BigDecimal("10.00"));

        when(transactionRepository.findRetryable(any(), any())).thenReturn(List.of(rec));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(a));
        when(balanceManager.reserve(1L, rec.getAmount(), "r2")).thenReturn(BalanceManager.ReserveResult.OK);
        when(accountRepository.debitIfAvailable(1L, rec.getAmount())).thenReturn(1);

        svc.reprocessPending();

        verify(transactionRepository).save(argThat(r -> "COMMITTED".equals(r.getStatus())));
        verify(balanceManager).commit(1L, rec.getAmount(), "r2");
    }
}
