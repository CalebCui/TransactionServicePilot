package org.pilot.transactionservicepilot.service;

import org.pilot.transactionservicepilot.entity.Account;
import org.pilot.transactionservicepilot.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final AccountRepository accountRepository;
    private final BalanceManager balanceManager;
    private final TransactionService transactionService;

    public SyncScheduler(AccountRepository accountRepository, BalanceManager balanceManager, TransactionService transactionService) {
        this.accountRepository = accountRepository;
        this.balanceManager = balanceManager;
        this.transactionService = transactionService;
    }

    @Scheduled(fixedDelayString = "${app.sync.run-interval-ms:30000}")
    public void reconcile() {
        // For MVP: ensure Redis has balance for all accounts
        List<Account> accounts = accountRepository.findAll();
        for (Account a : accounts) {
            // If Redis missing balance, populate it
            if (balanceManager.getBalance(a.getId()) == null) {
                log.info("Populating Redis balance for account {}", a.getId());
                balanceManager.populateBalance(a.getId(), a.getBalance(), a.getAvailableBalance(), a.getCurrency());
            }
        }

        // Reprocess pending transactions
        try {
            transactionService.reprocessPending();
        } catch (Exception e) {
            log.error("Error while reprocessing pending transactions", e);
        }
    }
}
