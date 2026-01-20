package org.pilot.transactionservicepilot.controller;

import org.pilot.transactionservicepilot.dto.TransactionRequest;
import org.pilot.transactionservicepilot.dto.TransactionResponse;
import org.pilot.transactionservicepilot.entity.Account;
import org.pilot.transactionservicepilot.repository.AccountRepository;
import org.pilot.transactionservicepilot.service.TransactionService;
import org.pilot.transactionservicepilot.service.BalanceManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class TransactionController {

    private final TransactionService transactionService;
    private final AccountRepository accountRepository;
    private final BalanceManager balanceManager;

    public TransactionController(TransactionService transactionService, AccountRepository accountRepository, BalanceManager balanceManager) {
        this.transactionService = transactionService;
        this.accountRepository = accountRepository;
        this.balanceManager = balanceManager;
    }

    @PostMapping("/transactions")
    public ResponseEntity<TransactionResponse> postTransaction(@RequestBody TransactionRequest req) {
        TransactionResponse res = transactionService.process(req);
        if ("COMMITTED".equalsIgnoreCase(res.getStatus())) {
            return ResponseEntity.ok(res);
        } else if ("PENDING".equalsIgnoreCase(res.getStatus())) {
            return ResponseEntity.accepted().body(res);
        } else {
            return ResponseEntity.status(400).body(res);
        }
    }

    @GetMapping("/accounts/{id}/balance")
    public ResponseEntity<?> getBalance(@PathVariable("id") Long id) {
        // Try cache first for low-latency
        try {
            BigDecimal cached = balanceManager.getBalance(id);
            BigDecimal available = balanceManager.getAvailable(id);
            if (cached != null) {
                java.util.Map<String, Object> map = new java.util.HashMap<>();
                map.put("accountId", id);
                map.put("balance", cached);
                map.put("available", available);
                map.put("currency", accountRepository.findById(id).map(Account::getCurrency).orElse(null));
                return ResponseEntity.ok(map);
            }
        } catch (Exception e) {
            // ignore cache errors and fallback to DB
        }

        return accountRepository.findById(id)
                .map(a -> {
                    java.util.Map<String, Object> map = new java.util.HashMap<>();
                    map.put("accountId", a.getId());
                    map.put("balance", a.getBalance());
                    map.put("available", a.getAvailableBalance());
                    map.put("currency", a.getCurrency());
                    return ResponseEntity.ok(map);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
