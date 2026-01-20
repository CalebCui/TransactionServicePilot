package org.pilot.transactionservicepilot.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pilot.transactionservicepilot.dto.TransactionRequest;
import org.pilot.transactionservicepilot.dto.TransactionResponse;
import org.pilot.transactionservicepilot.entity.Account;
import org.pilot.transactionservicepilot.repository.AccountRepository;
import org.pilot.transactionservicepilot.service.BalanceManager;
import org.pilot.transactionservicepilot.service.TransactionService;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class TransactionControllerTest {

    TransactionService transactionService;
    AccountRepository accountRepository;
    BalanceManager balanceManager;
    TransactionController controller;

    @BeforeEach
    void setUp() {
        transactionService = mock(TransactionService.class);
        accountRepository = mock(AccountRepository.class);
        balanceManager = mock(BalanceManager.class);
        controller = new TransactionController(transactionService, accountRepository, balanceManager);
    }

    @Test
    void postTransaction_returns_ok_for_committed() {
        TransactionRequest req = new TransactionRequest(); req.setTxId("t1");
        when(transactionService.process(req)).thenReturn(new TransactionResponse("t1","COMMITTED", new BigDecimal("10.00"), null));
        ResponseEntity<TransactionResponse> r = controller.postTransaction(req);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void postTransaction_returns_accepted_for_pending_and_bad_request_for_failed() {
        TransactionRequest req = new TransactionRequest(); req.setTxId("t2");
        when(transactionService.process(req)).thenReturn(new TransactionResponse("t2","PENDING", null, null));
        assertThat(controller.postTransaction(req).getStatusCode().value()).isEqualTo(202);

        when(transactionService.process(req)).thenReturn(new TransactionResponse("t2","FAILED", null, "err"));
        assertThat(controller.postTransaction(req).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void getBalance_prefers_cache_then_db_and_notfound() {
        Long id = 10L;
        when(balanceManager.getBalance(id)).thenReturn(new BigDecimal("5.00"));
        when(balanceManager.getAvailable(id)).thenReturn(new BigDecimal("2.00"));
        when(accountRepository.findById(id)).thenReturn(Optional.of(new Account()));

        ResponseEntity<?> r = controller.getBalance(id);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).isNotNull();
        Map<String, Object> body = (Map<String, Object>) r.getBody();
        assertThat(body.get("balance")).isEqualTo(new BigDecimal("5.00"));

        // simulate cache error and fallback to DB
        when(balanceManager.getBalance(id)).thenThrow(new RuntimeException("boom"));
        Account a = new Account(); a.setId(id); a.setBalance(new BigDecimal("8.00")); a.setAvailableBalance(new BigDecimal("8.00")); a.setCurrency("USD");
        when(accountRepository.findById(id)).thenReturn(Optional.of(a));
        ResponseEntity<?> r2 = controller.getBalance(id);
        assertThat(r2.getStatusCode().value()).isEqualTo(200);

        // not found
        when(accountRepository.findById(id)).thenReturn(Optional.empty());
        ResponseEntity<?> r3 = controller.getBalance(id);
        assertThat(r3.getStatusCode().value()).isEqualTo(404);
    }
}
