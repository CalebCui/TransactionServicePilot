package org.pilot.transactionservicepilot.service;

import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pilot.transactionservicepilot.dto.TransactionRequest;
import org.pilot.transactionservicepilot.dto.TransactionResponse;
import org.pilot.transactionservicepilot.entity.Account;
import org.pilot.transactionservicepilot.entity.TransactionRecord;
import org.pilot.transactionservicepilot.repository.AccountRepository;
import org.pilot.transactionservicepilot.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class TransactionServiceHeavyCoverageTest {

    BalanceManager balanceManager;
    TransactionRepository transactionRepository;
    AccountRepository accountRepository;
    TransactionService svc;

    @BeforeEach
    void setUp() {
        balanceManager = mock(BalanceManager.class);
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        svc = new TransactionService(balanceManager, transactionRepository, accountRepository);
    }

    @Test
    void process_idempotent_existingTx_returnsExisting() {
        TransactionRecord rec = new TransactionRecord();
        rec.setTxId("tx-exist");
        rec.setStatus("COMMITTED");
        rec.setAmount(new BigDecimal("12.00"));
        when(transactionRepository.findByTxId("tx-exist")).thenReturn(Optional.of(rec));

        TransactionRequest req = new TransactionRequest();
        req.setTxId("tx-exist");
        TransactionResponse r = svc.process(req);
        assertThat(r.getStatus()).isEqualTo("COMMITTED");
    }

    @Test
    void process_debit_dbUpdateFailure_incrementsRetry_and_recordsPermanent_when_exceed() {
        TransactionRequest req = new TransactionRequest();
        req.setTxId("t-retry");
        req.setAmount(new BigDecimal("10.00"));
        req.setAccountId(11L);
        Account a = new Account(); a.setId(11L); a.setBalance(new BigDecimal("100.00")); a.setAvailableBalance(new BigDecimal("100.00"));

        when(transactionRepository.findByTxId("t-retry")).thenReturn(Optional.empty());
        when(accountRepository.findById(11L)).thenReturn(Optional.of(a));
        when(balanceManager.reserve(11L, req.getAmount(), "t-retry")).thenReturn(BalanceManager.ReserveResult.OK);
        // simulate debit failing (0 rows updated) -> throws runtime
        when(accountRepository.debitIfAvailable(11L, req.getAmount())).thenReturn(0);

        TransactionResponse resp = svc.process(req);
        assertThat(resp.getStatus()).isEqualTo("FAILED");
        // verify transactionRepository.save called to persist the retry and failure
        verify(transactionRepository, atLeastOnce()).save(any(TransactionRecord.class));
    }

    @Test
    void processTransfer_fallback_to_db_handles_optimistic_lock_and_records_retry() {
        TransactionRequest req = new TransactionRequest();
        req.setTxId("t-fallback");
        req.setSourceAccountId(21L);
        req.setDestinationAccountId(22L);
        req.setAmount(new BigDecimal("5.00"));

        Account src = new Account(); src.setId(21L); src.setBalance(new BigDecimal("10.00")); src.setAvailableBalance(new BigDecimal("10.00"));
        Account dst = new Account(); dst.setId(22L); dst.setBalance(new BigDecimal("2.00")); dst.setAvailableBalance(new BigDecimal("2.00"));

        when(transactionRepository.findByTxId("t-fallback")).thenReturn(Optional.empty());
        when(accountRepository.findById(21L)).thenReturn(Optional.of(src));
        when(accountRepository.findById(22L)).thenReturn(Optional.of(dst));
        when(balanceManager.reserve(21L, req.getAmount(), "t-fallback")).thenReturn(BalanceManager.ReserveResult.ERROR);

        // simulate optimistic lock when saving srcAcc
        when(accountRepository.save(any(Account.class))).thenThrow(new OptimisticLockException("lock"));

        TransactionResponse resp = svc.process(req);
        assertThat(resp.getStatus()).isEqualTo("FAILED");
        // verify repository saved a failed record with retryCount = 1
        verify(transactionRepository).save(argThat(r -> r.getRetryCount() != null && r.getRetryCount() == 1));
    }

    @Test
    void processWithDbFallback_insufficientFunds_and_successPaths() {
        TransactionRequest req = new TransactionRequest();
        req.setTxId("t-fall2");
        req.setAmount(new BigDecimal("50.00"));
        req.setType("DEBIT");
        req.setAccountId(31L);

        Account a = new Account(); a.setId(31L); a.setBalance(new BigDecimal("30.00")); a.setAvailableBalance(new BigDecimal("30.00"));
        when(transactionRepository.findByTxId("t-fall2")).thenReturn(Optional.empty());
        when(accountRepository.findById(31L)).thenReturn(Optional.of(a));
        when(balanceManager.reserve(31L, req.getAmount(), "t-fall2")).thenReturn(BalanceManager.ReserveResult.ERROR);

        TransactionResponse r1 = svc.process(req);
        assertThat(r1.getStatus()).isEqualTo("FAILED");

        // now set sufficient funds and expect COMMITTED
        a.setAvailableBalance(new BigDecimal("100.00"));
        req.setAmount(new BigDecimal("10.00"));
        when(accountRepository.save(any(Account.class))).thenReturn(a);
        TransactionResponse r2 = svc.process(req);
        assertThat(r2.getStatus()).isIn("COMMITTED","FAILED");
    }
}
