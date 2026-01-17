package org.pilot.transactionservicepilot.service;

import org.pilot.transactionservicepilot.dto.TransactionRequest;
import org.pilot.transactionservicepilot.dto.TransactionResponse;
import org.pilot.transactionservicepilot.entity.Account;
import org.pilot.transactionservicepilot.entity.TransactionRecord;
import org.pilot.transactionservicepilot.repository.AccountRepository;
import org.pilot.transactionservicepilot.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final BalanceManager balanceManager;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    private static final int MAX_RETRIES = 3;

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
            // increment retry count
            Integer rc = rec.getRetryCount() == null ? 0 : rec.getRetryCount();
            rec.setRetryCount(rc + 1);
            transactionRepository.save(rec);
            log.error("Transaction {} failed during process: {}", req.getTxId(), e.getMessage());
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
            Integer rc = rec.getRetryCount() == null ? 0 : rec.getRetryCount();
            rec.setRetryCount(rc + 1);
            transactionRepository.save(rec);
            log.error("Transfer {} failed: {}", req.getTxId(), e.getMessage());
            return new TransactionResponse(req.getTxId(), "FAILED", null, e.getMessage());
        }
    }

    private TransactionResponse processTransferWithDb(TransactionRequest req, Long src, Long dst) {
        // Fallback DB-only path (no Redis reservations)
        try {
            // Try to atomically debit source
            int debitUpdated = accountRepository.debitIfAvailable(src, req.getAmount());
            if (debitUpdated == 0) {
                return new TransactionResponse(req.getTxId(), "FAILED", null, "Insufficient funds or concurrent modification");
            }
            int creditUpdated = accountRepository.credit(dst, req.getAmount());
            if (creditUpdated == 0) {
                // improbable: credit failed, try to refund debit (best effort)
                accountRepository.credit(src, req.getAmount());
                return new TransactionResponse(req.getTxId(), "FAILED", null, "Credit failed after debit");
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
            // direct DB update
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
                int updated = accountRepository.debitIfAvailable(account.getId(), req.getAmount());
                if (updated == 0) {
                    rec.setStatus("FAILED");
                    rec.setProcessedAt(Instant.now());
                    rec.setError("Concurrent modification or insufficient funds");
                    transactionRepository.save(rec);
                    return new TransactionResponse(req.getTxId(), "FAILED", null, "Concurrent modification or insufficient funds");
                }
            } else {
                int updated = accountRepository.credit(account.getId(), req.getAmount());
                if (updated == 0) {
                    rec.setStatus("FAILED");
                    rec.setProcessedAt(Instant.now());
                    rec.setError("Credit failed");
                    transactionRepository.save(rec);
                    return new TransactionResponse(req.getTxId(), "FAILED", null, "Credit failed");
                }
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
        List<String> statuses = List.of("PENDING", "FAILED");
        List<TransactionRecord> list = transactionRepository.findByStatusIn(statuses);
        for (TransactionRecord rec : list) {
            try {
                if ("COMMITTED".equalsIgnoreCase(rec.getStatus())) continue;

                Integer retries = rec.getRetryCount() == null ? 0 : rec.getRetryCount();
                if (retries >= MAX_RETRIES) {
                    rec.setStatus("PERMANENTLY_FAILED");
                    rec.setProcessedAt(Instant.now());
                    transactionRepository.save(rec);
                    // Log permanent failure (user requested logging instead of requeue)
                    log.error("Transaction {} reached max retries ({}). Marked PERMANENTLY_FAILED.", rec.getTxId(), retries);
                    continue;
                }

                Optional<Account> accountOpt = accountRepository.findById(rec.getAccountId());
                if (accountOpt.isEmpty()) {
                    rec.setRetryCount(retries + 1);
                    rec.setError("Account not found");
                    rec.setProcessedAt(Instant.now());
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
                            if (updated == 0) throw new RuntimeException("DB commit failed");
                        }
                        account.setUpdatedAt(Instant.now());
                        accountRepository.save(account);

                        rec.setStatus("COMMITTED");
                        rec.setProcessedAt(Instant.now());
                        transactionRepository.save(rec);

                        balanceManager.commit(account.getId(), rec.getAmount(), rec.getTxId());
                    } catch (Exception e) {
                        balanceManager.rollback(account.getId(), rec.getAmount(), rec.getTxId());
                        rec.setRetryCount(retries + 1);
                        rec.setError("DB commit failed: " + e.getMessage());
                        rec.setProcessedAt(Instant.now());
                        transactionRepository.save(rec);
                    }
                } else if (reserveResult == BalanceManager.ReserveResult.INSUFFICIENT_FUNDS) {
                    rec.setRetryCount(retries + 1);
                    rec.setError("Insufficient funds");
                    rec.setProcessedAt(Instant.now());
                    transactionRepository.save(rec);
                } else if (reserveResult == BalanceManager.ReserveResult.NO_ACCOUNT) {
                    rec.setRetryCount(retries + 1);
                    rec.setError("Account not in cache");
                    rec.setProcessedAt(Instant.now());
                    transactionRepository.save(rec);
                } else {
                    // Redis error: fallback to DB update
                    TransactionRequest req = new TransactionRequest();
                    req.setTxId(rec.getTxId());
                    req.setAccountId(rec.getAccountId());
                    req.setType(rec.getType());
                    req.setAmount(rec.getAmount());
                    req.setCurrency(rec.getCurrency());

                    TransactionResponse resp = processWithDbFallback(req, account);
                    if ("COMMITTED".equalsIgnoreCase(resp.getStatus())) {
                        rec.setStatus("COMMITTED");
                        rec.setProcessedAt(Instant.now());
                        transactionRepository.save(rec);
                    } else {
                        rec.setRetryCount(retries + 1);
                        rec.setError(resp.getError());
                        rec.setProcessedAt(Instant.now());
                        transactionRepository.save(rec);
                    }
                }
            } catch (Exception ex) {
                // best effort: increment retry and persist
                Integer rc = rec.getRetryCount() == null ? 0 : rec.getRetryCount();
                rec.setRetryCount(rc + 1);
                rec.setError(ex.getMessage());
                rec.setProcessedAt(Instant.now());
                transactionRepository.save(rec);
            }
        }
    }
}
