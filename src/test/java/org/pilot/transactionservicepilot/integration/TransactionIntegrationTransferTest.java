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
public class TransactionIntegrationTransferTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private BalanceManager balanceManager;

    @Test
    void integrationTransferFlow() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        Account src = new Account();
        src.setAccountNumber("acct-src-100");
        src.setCurrency("USD");
        src.setBalance(new BigDecimal("500.00"));
        src.setAvailableBalance(new BigDecimal("500.00"));
        Account s = accountRepository.save(src);

        Account dst = new Account();
        dst.setAccountNumber("acct-dst-200");
        dst.setCurrency("USD");
        dst.setBalance(new BigDecimal("100.00"));
        dst.setAvailableBalance(new BigDecimal("100.00"));
        Account d = accountRepository.save(dst);

        // populate balances
        balanceManager.populateBalance(s.getId(), s.getBalance(), s.getAvailableBalance(), s.getCurrency());
        balanceManager.populateBalance(d.getId(), d.getBalance(), d.getAvailableBalance(), d.getCurrency());

        TransactionRequest req = new TransactionRequest();
        req.setTxId("itx-transfer-1");
        req.setSourceAccountId(s.getId());
        req.setDestinationAccountId(d.getId());
        req.setAmount(new BigDecimal("50.00"));
        req.setCurrency("USD");

        TransactionResponse res = transactionService.process(req);
        assertEquals("COMMITTED", res.getStatus());

        Account afterSrc = accountRepository.findById(s.getId()).orElseThrow();
        Account afterDst = accountRepository.findById(d.getId()).orElseThrow();
        assertEquals(0, afterSrc.getBalance().compareTo(new BigDecimal("450.00")));
        assertEquals(0, afterDst.getBalance().compareTo(new BigDecimal("150.00")));
    }
}
