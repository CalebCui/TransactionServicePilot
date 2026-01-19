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
public class TransactionIntegrationCreditTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private BalanceManager balanceManager;

    @Test
    void integrationCreditFlow() {
        // clean
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        Account a = new Account();
        a.setAccountNumber("acct-c-100");
        a.setCurrency("USD");
        a.setBalance(new BigDecimal("100.00"));
        a.setAvailableBalance(new BigDecimal("100.00"));
        Account saved = accountRepository.save(a);

        // populate in-memory balance
        balanceManager.populateBalance(saved.getId(), saved.getBalance(), saved.getAvailableBalance(), saved.getCurrency());

        TransactionRequest req = new TransactionRequest();
        req.setTxId("itx-credit-1");
        req.setAccountId(saved.getId());
        req.setType("CREDIT");
        req.setAmount(new BigDecimal("50.00"));
        req.setCurrency("USD");

        TransactionResponse res = transactionService.process(req);
        assertEquals("COMMITTED", res.getStatus());

        Account after = accountRepository.findById(saved.getId()).orElseThrow();
        assertEquals(0, after.getBalance().compareTo(new BigDecimal("150.00")));
    }
}
