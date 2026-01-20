package org.pilot.transactionservicepilot.service;

import org.pilot.transactionservicepilot.entity.Account;
import org.pilot.transactionservicepilot.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;

@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final AccountRepository accountRepository;
    private final BalanceManager balanceManager;
    private final TransactionService transactionService;

    @Value("${app.sync.base-backoff-seconds:5}")
    private long baseBackoffSeconds = 5L;

    @Value("${app.sync.max-retries:3}")
    private int maxRetries = 3;

    // optional metrics collector; injected if present in app
    private final MeterRegistry meterRegistry;

    // single constructor: MeterRegistry is optional (@Nullable)
    @Autowired
    public SyncScheduler(AccountRepository accountRepository, BalanceManager balanceManager, TransactionService transactionService, @Nullable MeterRegistry meterRegistry) {
        this.accountRepository = accountRepository;
        this.balanceManager = balanceManager;
        this.transactionService = transactionService;
        this.meterRegistry = meterRegistry;
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

        // Reprocess pending transactions with retry/backoff
        int attempt = 0;
        while (true) {
            try {
                attempt++;
                transactionService.reprocessPending();
                // success - record retry metric (optional) and break
                if (meterRegistry != null) {
                    meterRegistry.counter("sync_scheduler.attempt.count").increment();
                }
                break;
            } catch (Exception e) {
                log.error("Error while reprocessing pending transactions on attempt {}", attempt, e);
                if (meterRegistry != null) {
                    meterRegistry.counter("sync_scheduler.attempt.failure.count").increment();
                }
                if (attempt >= maxRetries) {
                    // permanent failure: log and emit metric; do not requeue
                    log.error("Permanent failure while reprocessing pending transactions after {} attempts", attempt, e);
                    if (meterRegistry != null) {
                        meterRegistry.counter("sync_scheduler.permanentFailure.count").increment();
                    }
                    break;
                }

                // exponential backoff before next retry
                long delaySeconds = baseBackoffSeconds * (1L << Math.max(0, attempt - 1));
                try {
                    Thread.sleep(delaySeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("SyncScheduler retry sleep interrupted");
                    break;
                }
            }
        }
    }
}
