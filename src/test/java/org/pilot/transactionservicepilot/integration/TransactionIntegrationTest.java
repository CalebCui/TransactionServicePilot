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
public class TransactionIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private BalanceManager balanceManager; // this will be the InMemoryBalanceManager in tests

    @Test
    void integrationDebitFlow() {
        Account a = new Account();
        a.setAccountNumber("acct-100");
        a.setCurrency("USD");
        a.setBalance(new BigDecimal("1000.00"));
        a.setAvailableBalance(new BigDecimal("1000.00"));
        Account saved = accountRepository.save(a);

        // Populate in-memory balance cache so BalanceManager.reserve will find the account
        balanceManager.populateBalance(saved.getId(), saved.getBalance(), saved.getAvailableBalance(), saved.getCurrency());

        TransactionRequest req = new TransactionRequest();
        req.setTxId("itx-1");
        req.setAccountId(saved.getId());
        req.setType("DEBIT");
        req.setAmount(new BigDecimal("100.00"));
        req.setCurrency("USD"); // ensure currency is set for DB not-null constraint

        TransactionResponse res = transactionService.process(req);
        assertEquals("COMMITTED", res.getStatus());

        Account after = accountRepository.findById(saved.getId()).orElseThrow();
        // use compareTo to ignore scale differences
        assertEquals(0, after.getBalance().compareTo(new BigDecimal("900.00")));
    }
}
