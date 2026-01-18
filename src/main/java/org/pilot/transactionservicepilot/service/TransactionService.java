package org.pilot.transactionservicepilot.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.pilot.transactionservicepilot.dto.TransactionRequest;
import org.pilot.transactionservicepilot.dto.TransactionResponse;
import org.pilot.transactionservicepilot.entity.Account;
import org.pilot.transactionservicepilot.entity.TransactionRecord;
import org.pilot.transactionservicepilot.repository.AccountRepository;
import org.pilot.transactionservicepilot.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final BalanceManager balanceManager;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    @Value("${app.sync.base-backoff-seconds:5}")
    private long baseBackoffSeconds = 5L;

    @Value("${app.sync.max-retries:3}")
    private int maxRetries = 3;

    // keep legacy constant for backward compatibility in code areas that expect a constant (not strictly required)
    private static final int MAX_RETRIES = -1; // deprecated; use `maxRetries` instance field

    private MeterRegistry meterRegistry;

    // optional injection by Spring; tests that construct TransactionService directly may leave this null
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TransactionService(BalanceManager balanceManager, TransactionRepository transactionRepository, AccountRepository accountRepository, MeterRegistry meterRegistry) {
        this.balanceManager = balanceManager;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.meterRegistry = meterRegistry;
    }

    public TransactionService(BalanceManager balanceManager, TransactionRepository transactionRepository, AccountRepository accountRepository) {
        this.balanceManager = balanceManager;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional
    public TransactionResponse process(TransactionRequest req) {
        // idempotency check
        Optional<TransactionRecord> existing = transactionRepository.findByTxId(req.getTxId());
        if (existing.isPresent()) {
            TransactionRecord r = existing.get();
            return new TransactionResponse(r.getTxId(), r.getStatus(), r.getAmount(), r.getError());
        }

        // basic validation
        if (req.getAmount() == null || req.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            return new TransactionResponse(req.getTxId(), "FAILED", null, "Invalid amount");
        }

        // Choose transfer vs single-account flows
        if (req.getSourceAccountId() != null && req.getDestinationAccountId() != null) {
            return processTransfer(req);
        }

        // legacy single-account handling (debit/credit on accountId)
        Optional<Account> accountOpt = accountRepository.findById(req.getAccountId());
        if (accountOpt.isEmpty()) {
            return new TransactionResponse(req.getTxId(), "FAILED", null, "Account not found");
        }
        Account account = accountOpt.get();

        // Reserve in Redis
        BalanceManager.ReserveResult reserveResult = balanceManager.reserve(account.getId(), req.getAmount(), req.getTxId());
        if (reserveResult == BalanceManager.ReserveResult.NO_ACCOUNT) {
            return new TransactionResponse(req.getTxId(), "FAILED", null, "Account not in cache");
        }
        if (reserveResult == BalanceManager.ReserveResult.INSUFFICIENT_FUNDS) {
            return new TransactionResponse(req.getTxId(), "FAILED", null, "Insufficient funds");
        }
        if (reserveResult == BalanceManager.ReserveResult.ERROR) {
            // fallback: perform DB-only update
            return processWithDbFallback(req, account);
        }

        // create transaction record (PENDING)
        TransactionRecord rec = new TransactionRecord();
        rec.setTxId(req.getTxId());
        rec.setAccountId(account.getId());
        rec.setType(req.getType());
        rec.setAmount(req.getAmount());
        rec.setCurrency(req.getCurrency());
        rec.setStatus("PENDING");
        rec.setTimestamp(req.getTimestamp());
        transactionRepository.save(rec);

        try {
            // update DB account balance using DB-side conditional update to avoid optimistic lock churn
            if ("DEBIT".equalsIgnoreCase(req.getType())) {
                int updated = accountRepository.debitIfAvailable(account.getId(), req.getAmount());
                if (updated == 0) throw new RuntimeException("Insufficient funds or concurrent modification");
            } else {
                int updated = accountRepository.credit(account.getId(), req.getAmount());
                if (updated == 0) throw new RuntimeException("Credit failed");
            }

            // mark transaction committed
            rec.setStatus("COMMITTED");
            rec.setProcessedAt(Instant.now());
            transactionRepository.save(rec);

            // commit Redis reservation
            balanceManager.commit(account.getId(), req.getAmount(), req.getTxId());

            // fetch updated balance from DB to include in response
            BigDecimal updatedBal = accountRepository.findById(account.getId()).map(Account::getBalance).orElse(null);
            return new TransactionResponse(req.getTxId(), "COMMITTED", updatedBal, null);
        } catch (Exception e) {
            // rollback reservation
            balanceManager.rollback(account.getId(), req.getAmount(), req.getTxId());
            rec.setStatus("FAILED");
            rec.setError(e.getMessage());
            rec.setProcessedAt(Instant.now());
            // increment retry count and schedule next attempt or mark permanent
            int rc = rec.getRetryCount() == null ? 0 : rec.getRetryCount();
            rec.setRetryCount(rc + 1);
            if (rec.getRetryCount() >= maxRetries) {
                rec.setStatus("FAILED");
                recordPermanentFailure(rec);
                rec.setNextAttemptAt(null);
                log.error("Permanent failure processing transaction {} after {} retries: {}", req.getTxId(), rec.getRetryCount(), e.toString());
            } else {
                rec.setNextAttemptAt(calculateNextAttempt(Instant.now(), rec.getRetryCount()));
                log.error("Transaction {} failed during process, scheduled retry {} at {}: {}", req.getTxId(), rec.getRetryCount(), rec.getNextAttemptAt(), e.getMessage());
            }
            transactionRepository.save(rec);
            return new TransactionResponse(req.getTxId(), "FAILED", null, e.getMessage());
        }
    }

    private TransactionResponse processTransfer(TransactionRequest req) {
        Long src = req.getSourceAccountId();
        Long dst = req.getDestinationAccountId();
        if (src.equals(dst)) {
            return new TransactionResponse(req.getTxId(), "FAILED", null, "Source and destination cannot be same");
        }

        Optional<Account> srcOpt = accountRepository.findById(src);
        Optional<Account> dstOpt = accountRepository.findById(dst);
        if (srcOpt.isEmpty() || dstOpt.isEmpty()) {
            return new TransactionResponse(req.getTxId(), "FAILED", null, "Source or destination account not found");
        }

        // Reserve on source in Redis
        BalanceManager.ReserveResult reserveResult = balanceManager.reserve(src, req.getAmount(), req.getTxId());
        if (reserveResult == BalanceManager.ReserveResult.NO_ACCOUNT) {
            return new TransactionResponse(req.getTxId(), "FAILED", null, "Source account not in cache");
        }
        if (reserveResult == BalanceManager.ReserveResult.INSUFFICIENT_FUNDS) {
            return new TransactionResponse(req.getTxId(), "FAILED", null, "Insufficient funds");
        }
        if (reserveResult == BalanceManager.ReserveResult.ERROR) {
            // fallback to DB-side atomic update
            return processTransferWithDb(req, src, dst);
        }

        // create transaction record (PENDING)
        TransactionRecord rec = new TransactionRecord();
        rec.setTxId(req.getTxId());
        rec.setSourceAccountId(src);
        rec.setDestinationAccountId(dst);
        rec.setType("TRANSFER");
        rec.setAmount(req.getAmount());
        rec.setCurrency(req.getCurrency());
        rec.setStatus("PENDING");
        rec.setTimestamp(req.getTimestamp());
        transactionRepository.save(rec);

        try {
            // perform DB-side conditional debit and credit within the transaction
            int debitUpdated = accountRepository.debitIfAvailable(src, req.getAmount());
            if (debitUpdated == 0) throw new RuntimeException("Insufficient funds or concurrent modification");
            int creditUpdated = accountRepository.credit(dst, req.getAmount());
            if (creditUpdated == 0) throw new RuntimeException("Credit failed");

            rec.setStatus("COMMITTED");
            rec.setProcessedAt(Instant.now());
            transactionRepository.save(rec);

            // commit reservation on source
            balanceManager.commit(src, req.getAmount(), req.getTxId());

            // fetch updated balances for response (source balance)
            BigDecimal srcBal = accountRepository.findById(src).map(Account::getBalance).orElse(null);
            return new TransactionResponse(req.getTxId(), "COMMITTED", srcBal, null);
        } catch (Exception e) {
            // rollback reservation
            balanceManager.rollback(src, req.getAmount(), req.getTxId());
            rec.setStatus("FAILED");
            rec.setError(e.getMessage());
            rec.setProcessedAt(Instant.now());
            int rc = rec.getRetryCount() == null ? 0 : rec.getRetryCount();
            rec.setRetryCount(rc + 1);
            if (rec.getRetryCount() >= maxRetries) {
                rec.setStatus("FAILED");
                recordPermanentFailure(rec);
                rec.setNextAttemptAt(null);
                log.error("Permanent failure processing transfer {} after {} retries: {}", req.getTxId(), rec.getRetryCount(), e.toString());
            } else {
                rec.setNextAttemptAt(calculateNextAttempt(Instant.now(), rec.getRetryCount()));
                log.error("Transfer {} failed, scheduled retry {} at {}: {}", req.getTxId(), rec.getRetryCount(), rec.getNextAttemptAt(), e.getMessage());
            }
            transactionRepository.save(rec);
            return new TransactionResponse(req.getTxId(), "FAILED", null, e.getMessage());
        }
    }

    private TransactionResponse processTransferWithDb(TransactionRequest req, Long src, Long dst) {
        // Fallback DB-only path (no Redis reservations)
        try {
            // Use JPA entity updates (optimistic locking via @Version on Account)
            Optional<Account> srcOpt = accountRepository.findById(src);
            Optional<Account> dstOpt = accountRepository.findById(dst);
            if (srcOpt.isEmpty() || dstOpt.isEmpty()) {
                return new TransactionResponse(req.getTxId(), "FAILED", null, "Source or destination account not found");
            }
            Account srcAcc = srcOpt.get();
            Account dstAcc = dstOpt.get();

            // check availability
            if (srcAcc.getAvailableBalance().compareTo(req.getAmount()) < 0) {
                return new TransactionResponse(req.getTxId(), "FAILED", null, "Insufficient funds");
            }

            // perform updates in-memory and save; JPA will perform optimistic locking
            srcAcc.setAvailableBalance(srcAcc.getAvailableBalance().subtract(req.getAmount()));
            srcAcc.setBalance(srcAcc.getBalance().subtract(req.getAmount()));
            srcAcc.setUpdatedAt(Instant.now());

            dstAcc.setBalance(dstAcc.getBalance().add(req.getAmount()));
            dstAcc.setUpdatedAt(Instant.now());

            try {
                accountRepository.save(srcAcc);
                accountRepository.save(dstAcc);
            } catch (OptimisticLockException | OptimisticLockingFailureException ole) {
                // treat as transient: record retry and schedule backoff
                TransactionRecord rec = new TransactionRecord();
                rec.setTxId(req.getTxId());
                rec.setSourceAccountId(src);
                rec.setDestinationAccountId(dst);
                rec.setType("TRANSFER");
                rec.setAmount(req.getAmount());
                rec.setCurrency(req.getCurrency());
                rec.setStatus("FAILED");
                rec.setProcessedAt(Instant.now());
                rec.setError("Optimistic lock failure: " + ole.getMessage());
                rec.setRetryCount(1);
                if (rec.getRetryCount() >= maxRetries) {
                    recordPermanentFailure(rec);
                    rec.setNextAttemptAt(null);
                } else {
                    rec.setNextAttemptAt(calculateNextAttempt(Instant.now(), rec.getRetryCount()));
                }
                transactionRepository.save(rec);
                log.warn("Optimistic lock during transfer {}: {}", req.getTxId(), ole.getMessage());
                return new TransactionResponse(req.getTxId(), "FAILED", null, "Optimistic lock");
            }

            TransactionRecord rec = new TransactionRecord();
            rec.setTxId(req.getTxId());
            rec.setSourceAccountId(src);
            rec.setDestinationAccountId(dst);
            rec.setType("TRANSFER");
            rec.setAmount(req.getAmount());
            rec.setCurrency(req.getCurrency());
            rec.setStatus("COMMITTED");
            rec.setProcessedAt(Instant.now());
            rec.setTimestamp(req.getTimestamp());
            transactionRepository.save(rec);

            BigDecimal srcBal = accountRepository.findById(src).map(Account::getBalance).orElse(null);
            return new TransactionResponse(req.getTxId(), "COMMITTED", srcBal, null);
        } catch (Exception e) {
            log.error("Fallback DB transfer failed for {}: {}", req.getTxId(), e.getMessage());
            return new TransactionResponse(req.getTxId(), "FAILED", null, e.getMessage());
        }
    }

    private TransactionResponse processWithDbFallback(TransactionRequest req, Account account) {
        try {
            // Use JPA entity updates with optimistic locking
            TransactionRecord rec = new TransactionRecord();
            rec.setTxId(req.getTxId());
            rec.setAccountId(account.getId());
            rec.setType(req.getType());
            rec.setAmount(req.getAmount());
            rec.setCurrency(req.getCurrency());
            rec.setStatus("PENDING");
            transactionRepository.save(rec);

            if ("DEBIT".equalsIgnoreCase(req.getType())) {
                if (account.getAvailableBalance().compareTo(req.getAmount()) < 0) {
                    rec.setStatus("FAILED");
                    rec.setProcessedAt(Instant.now());
                    rec.setError("Insufficient funds");
                    transactionRepository.save(rec);
                    return new TransactionResponse(req.getTxId(), "FAILED", null, "Insufficient funds");
                }
                // modify entity and save (optimistic locking via @Version)
                account.setAvailableBalance(account.getAvailableBalance().subtract(req.getAmount()));
                account.setBalance(account.getBalance().subtract(req.getAmount()));
                account.setUpdatedAt(Instant.now());
                accountRepository.save(account);
            } else {
                account.setBalance(account.getBalance().add(req.getAmount()));
                account.setUpdatedAt(Instant.now());
                accountRepository.save(account);
            }

            rec.setStatus("COMMITTED");
            rec.setProcessedAt(Instant.now());
            transactionRepository.save(rec);

            BigDecimal newBal = accountRepository.findById(account.getId()).map(Account::getBalance).orElse(null);
            return new TransactionResponse(req.getTxId(), "COMMITTED", newBal, null);
        } catch (Exception e) {
            log.error("Fallback DB processing failed for {}: {}", req.getTxId(), e.getMessage());
            return new TransactionResponse(req.getTxId(), "FAILED", null, e.getMessage());
        }
    }

    // New: reprocess pending/failed transactions (called by scheduler)
    @Transactional
    public void reprocessPending() {
        // process PENDING or FAILED (retryable) transactions, but respect exponential backoff
        List<String> retryableStatuses = Arrays.asList("PENDING", "FAILED");
        List<TransactionRecord> candidates = transactionRepository.findRetryable(retryableStatuses, Instant.now());

        for (TransactionRecord rec : candidates) {
            try {
                // skip if someone else processed it
                if ("COMMITTED".equalsIgnoreCase(rec.getStatus())) continue;

                // eligibility guard (in case nextAttemptAt was set after query)
                Instant now = Instant.now();
                if (rec.getNextAttemptAt() != null && rec.getNextAttemptAt().isAfter(now)) {
                    continue;
                }

                int retries = rec.getRetryCount() == null ? 0 : rec.getRetryCount();

                Optional<Account> accountOpt = accountRepository.findById(rec.getAccountId());
                if (accountOpt.isEmpty()) {
                    rec.setRetryCount(retries + 1);
                    rec.setError("Account not found");
                    rec.setProcessedAt(Instant.now());
                    if (rec.getRetryCount() >= maxRetries) {
                        rec.setStatus("FAILED");
                        // permanent failure: log, record metric and do not reschedule
                        log.error("Permanent failure processing transaction {}: account {} not found after {} retries", rec.getTxId(), rec.getAccountId(), rec.getRetryCount());
                        // increment permanent failure metric so monitoring/test can observe it
                        recordPermanentFailure(rec);
                        rec.setNextAttemptAt(null);
                    } else {
                        // schedule next attempt
                        rec.setNextAttemptAt(calculateNextAttempt(now, rec.getRetryCount()));
                    }
                    transactionRepository.save(rec);
                    continue;
                }
                Account account = accountOpt.get();

                BalanceManager.ReserveResult reserveResult = balanceManager.reserve(account.getId(), rec.getAmount(), rec.getTxId());
                if (reserveResult == BalanceManager.ReserveResult.OK) {
                    try {
                        if ("DEBIT".equalsIgnoreCase(rec.getType())) {
                            int updated = accountRepository.debitIfAvailable(account.getId(), rec.getAmount());
                            if (updated == 0) throw new RuntimeException("Insufficient funds or concurrent modification");
                        } else {
                            int updated = accountRepository.credit(account.getId(), rec.getAmount());
                            if (updated == 0) throw new RuntimeException("Concurrent modification on credit");
                        }

                        // successful
                        balanceManager.commit(account.getId(), rec.getAmount(), rec.getTxId());
                        rec.setStatus("COMMITTED");
                        rec.setProcessedAt(Instant.now());
                        rec.setError(null);
                        rec.setNextAttemptAt(null);
                        transactionRepository.save(rec);

                    } catch (Exception e) {
                        // failure when trying to apply to DB
                        rec.setRetryCount(retries + 1);
                        rec.setError(e.getMessage());
                        rec.setProcessedAt(Instant.now());
                        if (rec.getRetryCount() >= maxRetries) {
                            rec.setStatus("FAILED");
                            // permanent failure: log as event and do not requeue
                            log.error("Permanent failure processing transaction {} account {} after {} retries: {}", rec.getTxId(), account.getId(), rec.getRetryCount(), e.toString());
                            // metrics
                            recordPermanentFailure(rec);
                            rec.setNextAttemptAt(null);
                        } else {
                            rec.setNextAttemptAt(calculateNextAttempt(now, rec.getRetryCount()));
                        }
                        transactionRepository.save(rec);
                        balanceManager.rollback(account.getId(), rec.getAmount(), rec.getTxId());
                    }
                } else {
                    // reservation failed in Redis — increment retry and schedule next attempt
                    rec.setRetryCount(retries + 1);
                    rec.setError("Redis reserve failed: " + reserveResult);
                    rec.setProcessedAt(Instant.now());
                    if (rec.getRetryCount() >= maxRetries) {
                        rec.setStatus("FAILED");
                        log.error("Permanent failure reserving transaction {} after {} retries: {}", rec.getTxId(), rec.getRetryCount(), reserveResult);
                        recordPermanentFailure(rec);
                        rec.setNextAttemptAt(null);
                    } else {
                        rec.setNextAttemptAt(calculateNextAttempt(now, rec.getRetryCount()));
                    }
                    transactionRepository.save(rec);
                }

            } catch (Exception e) {
                log.error("Unexpected error while reprocessing transaction {}: {}", rec.getTxId(), e.toString());
                // increment retry, schedule next attempt
                int rc = rec.getRetryCount() == null ? 1 : rec.getRetryCount() + 1;
                rec.setRetryCount(rc);
                rec.setProcessedAt(Instant.now());
                rec.setError(e.getMessage());
                if (rc >= maxRetries) {
                    rec.setStatus("FAILED");
                    log.error("Permanent failure reprocessing transaction {}: {}", rec.getTxId(), e.toString());
                    recordPermanentFailure(rec);
                    rec.setNextAttemptAt(null);
                } else {
                    rec.setNextAttemptAt(calculateNextAttempt(Instant.now(), rc));
                }
                transactionRepository.save(rec);
            }
        }
    }

    Instant calculateNextAttempt(Instant now, int retryCount) {
        long base = baseBackoffSeconds > 0 ? baseBackoffSeconds : 5L; // configurable base backoff seconds
        long delay = base * (1L << Math.max(0, retryCount - 1)); // exponential: base * 2^(retryCount-1)
        return now.plusSeconds(delay);
    }

    private void recordPermanentFailure(TransactionRecord rec) {
        // Increment a counter for permanently failed transactions
        if (meterRegistry != null) {
            meterRegistry.counter("transaction.permanentFailure.count", "type", rec.getType()).increment();
        }
    }
}
