package org.pilot.transactionservicepilot.integration;

import org.junit.jupiter.api.Test;
import org.pilot.transactionservicepilot.dto.TransactionRequest;
import org.pilot.transactionservicepilot.dto.TransactionResponse;
import org.pilot.transactionservicepilot.entity.Account;
import org.pilot.transactionservicepilot.repository.AccountRepository;
import org.pilot.transactionservicepilot.repository.TransactionRepository;
import org.pilot.transactionservicepilot.service.BalanceManager;
import org.pilot.transactionservicepilot.service.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class TransactionIntegrationIdempotencyTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private BalanceManager balanceManager;

    @Test
    void integrationIdempotency() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        Account a = new Account();
        a.setAccountNumber("acct-ido-100");
        a.setCurrency("USD");
        a.setBalance(new BigDecimal("200.00"));
        a.setAvailableBalance(new BigDecimal("200.00"));
        Account saved = accountRepository.save(a);

        balanceManager.populateBalance(saved.getId(), saved.getBalance(), saved.getAvailableBalance(), saved.getCurrency());

        TransactionRequest req = new TransactionRequest();
        req.setTxId("itx-id-1");
        req.setAccountId(saved.getId());
        req.setType("DEBIT");
        req.setAmount(new BigDecimal("50.00"));
        req.setCurrency("USD");

        TransactionResponse r1 = transactionService.process(req);
        assertEquals("COMMITTED", r1.getStatus());

        // re-run same TxId - should be idempotent (no double debit)
        TransactionResponse r2 = transactionService.process(req);
        assertEquals(r1.getStatus(), r2.getStatus());

        Account after = accountRepository.findById(saved.getId()).orElseThrow();
        assertEquals(0, after.getBalance().compareTo(new BigDecimal("150.00")));
    }
}
