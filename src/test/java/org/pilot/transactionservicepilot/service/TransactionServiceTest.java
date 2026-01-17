package org.pilot.transactionservicepilot.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pilot.transactionservicepilot.dto.TransactionRequest;
import org.pilot.transactionservicepilot.dto.TransactionResponse;
import org.pilot.transactionservicepilot.entity.Account;
import org.pilot.transactionservicepilot.entity.TransactionRecord;
import org.pilot.transactionservicepilot.repository.AccountRepository;
import org.pilot.transactionservicepilot.repository.TransactionRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransactionServiceTest {

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
    void testInsufficientFunds() {
        Account a = new Account();
        a.setId(1L);
        a.setBalance(new BigDecimal("50.00"));
        a.setAvailableBalance(new BigDecimal("50.00"));
        a.setCurrency("USD");

        when(accountRepository.findById(1L)).thenReturn(Optional.of(a));
        when(balanceManager.reserve(eq(1L), any(BigDecimal.class), eq("tx-1"))).thenReturn(BalanceManager.ReserveResult.INSUFFICIENT_FUNDS);

        TransactionRequest req = new TransactionRequest();
        req.setTxId("tx-1");
        req.setAccountId(1L);
        req.setType("DEBIT");
        req.setAmount(new BigDecimal("100.00"));

        TransactionResponse res = transactionService.process(req);
        assertEquals("FAILED", res.getStatus());
        assertTrue(res.getError().contains("Insufficient"));
    }

    @Test
    void testSuccessfulDebit() {
        Account a = new Account();
        a.setId(2L);
        a.setBalance(new BigDecimal("200.00"));
        a.setAvailableBalance(new BigDecimal("200.00"));
        a.setCurrency("USD");

        when(accountRepository.findById(2L)).thenReturn(Optional.of(a));
        when(balanceManager.reserve(eq(2L), any(BigDecimal.class), eq("tx-2"))).thenReturn(BalanceManager.ReserveResult.OK);
        when(transactionRepository.findByTxId("tx-2")).thenReturn(Optional.empty());
        when(transactionRepository.save(any(TransactionRecord.class))).thenAnswer(i -> i.getArguments()[0]);
        // mock DB-side update to succeed
        when(accountRepository.debitIfAvailable(eq(2L), any(BigDecimal.class))).thenReturn(1);
        when(accountRepository.save(any(Account.class))).thenAnswer(i -> i.getArguments()[0]);

        TransactionRequest req = new TransactionRequest();
        req.setTxId("tx-2");
        req.setAccountId(2L);
        req.setType("DEBIT");
        req.setAmount(new BigDecimal("10.00"));

        TransactionResponse res = transactionService.process(req);
        assertEquals("COMMITTED", res.getStatus());
    }
}
