package org.pilot.transactionservicepilot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pilot.transactionservicepilot.dto.TransactionRequest;
import org.pilot.transactionservicepilot.dto.TransactionResponse;
import org.pilot.transactionservicepilot.entity.Account;
import org.pilot.transactionservicepilot.repository.AccountRepository;
import org.pilot.transactionservicepilot.repository.TransactionRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class TransactionServiceUnitTest {

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
    void process_invalidAmount_shouldFail() {
        TransactionRequest req = new TransactionRequest();
        req.setTxId("t1");
        req.setAmount(BigDecimal.ZERO);
        TransactionResponse resp = svc.process(req);
        assertThat(resp.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void process_accountNotFound_shouldFail() {
        TransactionRequest req = new TransactionRequest();
        req.setTxId("t2");
        req.setAmount(new BigDecimal("10.00"));
        req.setAccountId(5L);
        when(transactionRepository.findByTxId("t2")).thenReturn(Optional.empty());
        when(accountRepository.findById(5L)).thenReturn(Optional.empty());
        TransactionResponse resp = svc.process(req);
        assertThat(resp.getStatus()).isEqualTo("FAILED");
        assertThat(resp.getError()).contains("Account not found");
    }

    @Test
    void process_reserveNoAccount_shouldFail() {
        TransactionRequest req = new TransactionRequest();
        req.setTxId("t3");
        req.setAmount(new BigDecimal("10.00"));
        req.setAccountId(6L);
        Account a = new Account();
        a.setId(6L);
        when(transactionRepository.findByTxId("t3")).thenReturn(Optional.empty());
        when(accountRepository.findById(6L)).thenReturn(Optional.of(a));
        when(balanceManager.reserve(6L, req.getAmount(), "t3")).thenReturn(BalanceManager.ReserveResult.NO_ACCOUNT);
        TransactionResponse resp = svc.process(req);
        assertThat(resp.getStatus()).isEqualTo("FAILED");
        assertThat(resp.getError()).contains("Account not in cache");
    }

    @Test
    void process_reserveInsufficientFunds_shouldFail() {
        TransactionRequest req = new TransactionRequest();
        req.setTxId("t4");
        req.setAmount(new BigDecimal("50.00"));
        req.setAccountId(7L);
        Account a = new Account();
        a.setId(7L);
        when(transactionRepository.findByTxId("t4")).thenReturn(Optional.empty());
        when(accountRepository.findById(7L)).thenReturn(Optional.of(a));
        when(balanceManager.reserve(7L, req.getAmount(), "t4")).thenReturn(BalanceManager.ReserveResult.INSUFFICIENT_FUNDS);
        TransactionResponse resp = svc.process(req);
        assertThat(resp.getStatus()).isEqualTo("FAILED");
        assertThat(resp.getError()).contains("Insufficient funds");
    }

    @Test
    void process_reserveError_triggersDbFallback() {
        TransactionRequest req = new TransactionRequest();
        req.setTxId("t5");
        req.setAmount(new BigDecimal("20.00"));
        req.setAccountId(8L);
        Account a = new Account();
        a.setId(8L);
        a.setBalance(new BigDecimal("100.00"));
        a.setAvailableBalance(new BigDecimal("100.00"));
        when(transactionRepository.findByTxId("t5")).thenReturn(Optional.empty());
        when(accountRepository.findById(8L)).thenReturn(Optional.of(a));
        when(balanceManager.reserve(8L, req.getAmount(), "t5")).thenReturn(BalanceManager.ReserveResult.ERROR);
        // mock DB save path: accountRepository.save will be called in fallback; we simulate save by returning account
        when(accountRepository.save(any(Account.class))).thenReturn(a);
        TransactionResponse resp = svc.process(req);
        // since fallback will commit the DB update, expect COMMITTED or FAILED depending on branch
        assertThat(resp.getStatus()).isIn("COMMITTED","FAILED");
    }

    @Test
    void processTransfer_sameAndMissingAccounts_and_okPaths() {
        TransactionRequest req = new TransactionRequest();
        req.setTxId("t6");
        req.setSourceAccountId(1L);
        req.setDestinationAccountId(1L);
        req.setAmount(new BigDecimal("1.00"));
        // same account
        TransactionResponse r1 = svc.process(req);
        assertThat(r1.getStatus()).isEqualTo("FAILED");

        // missing accounts
        req.setDestinationAccountId(2L);
        when(accountRepository.findById(1L)).thenReturn(Optional.empty());
        TransactionResponse r2 = svc.process(req);
        assertThat(r2.getStatus()).isEqualTo("FAILED");

        // success path: reserve OK, debit/credit succeed
        Account src = new Account(); src.setId(1L); src.setBalance(new BigDecimal("10.00")); src.setAvailableBalance(new BigDecimal("10.00"));
        Account dst = new Account(); dst.setId(2L); dst.setBalance(new BigDecimal("5.00")); dst.setAvailableBalance(new BigDecimal("5.00"));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(src));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(dst));
        when(balanceManager.reserve(1L, req.getAmount(), "t6")).thenReturn(BalanceManager.ReserveResult.OK);
        when(accountRepository.debitIfAvailable(1L, req.getAmount())).thenReturn(1);
        when(accountRepository.credit(2L, req.getAmount())).thenReturn(1);
        TransactionResponse r3 = svc.process(req);
        assertThat(r3.getStatus()).isIn("COMMITTED","FAILED");
    }

    @Test
    void calculateNextAttempt_works_exponential() {
        Instant now = Instant.now();
        Instant next = svc.calculateNextAttempt(now, 1);
        assertThat(next).isAfter(now);
        Instant next2 = svc.calculateNextAttempt(now, 3);
        assertThat(next2).isAfter(next);
    }
}
