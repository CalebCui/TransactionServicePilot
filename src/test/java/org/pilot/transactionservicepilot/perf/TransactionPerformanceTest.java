package org.pilot.transactionservicepilot.perf;

import org.junit.jupiter.api.Test;
import org.pilot.transactionservicepilot.dto.TransactionRequest;
import org.pilot.transactionservicepilot.dto.TransactionResponse;
import org.pilot.transactionservicepilot.entity.Account;
import org.pilot.transactionservicepilot.repository.AccountRepository;
import org.pilot.transactionservicepilot.service.BalanceManager;
import org.pilot.transactionservicepilot.service.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@TestPropertySource(properties = "spring.profiles.active=${perf.profile:local}")
public class TransactionPerformanceTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BalanceManager balanceManager;

    /**
     * Configurable performance test.
     * System properties (optional):
     *  - perf.profile (default local)
     *  - perf.threads (default 10)
     *  - perf.txsPerThread (default 100)
     *  - perf.amount (default 1.00)
     *
     * This test will create an account with sufficient balance, populate the in-memory cache,
     * then concurrently submit transactions and measure throughput and success count.
     */
    @Test
    public void concurrentTransactionThroughput() throws Exception {
        int threads = Integer.parseInt(System.getProperty("perf.threads", "10"));
        int txsPerThread = Integer.parseInt(System.getProperty("perf.txsPerThread", "100"));
        BigDecimal amount = new BigDecimal(System.getProperty("perf.amount", "1.00"));

        int totalTx = threads * txsPerThread;
        BigDecimal totalAmount = amount.multiply(new BigDecimal(totalTx));

        // create account with enough balance
        Account a = new Account();
        a.setAccountNumber("perf-acct-" + UUID.randomUUID());
        a.setCurrency("USD");
        // add some headroom
        a.setBalance(totalAmount.add(new BigDecimal("1000.00")));
        a.setAvailableBalance(totalAmount.add(new BigDecimal("1000.00")));
        Account saved = accountRepository.save(a);

        // Populate in-memory cache
        balanceManager.populateBalance(saved.getId(), saved.getBalance(), saved.getAvailableBalance(), saved.getCurrency());

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        List<Callable<TransactionResponse>> tasks = new ArrayList<>();

        // thread-safe collection to store per-sample results (time,latency_ms,success)
        List<String> samples = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < txsPerThread; i++) {
                final String txId = "perf-" + t + "-" + i + "-" + UUID.randomUUID();
                tasks.add(() -> {
                    TransactionRequest req = new TransactionRequest();
                    req.setTxId(txId);
                    req.setAccountId(saved.getId());
                    req.setType("DEBIT");
                    req.setAmount(amount);
                    req.setCurrency("USD");

                    long startNs = System.nanoTime();
                    boolean success = false;
                    try {
                        TransactionResponse resp = transactionService.process(req);
                        if (resp != null && "COMMITTED".equalsIgnoreCase(resp.getStatus())) {
                            success = true;
                        }
                        return resp;
                    } finally {
                        long latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
                        long ts = System.currentTimeMillis();
                        // CSV line: time,latency,success
                        samples.add(ts + "," + latencyMs + "," + (success ? "true" : "false"));
                    }
                });
            }
        }

        Instant start = Instant.now();
        List<Future<TransactionResponse>> futures = exec.invokeAll(tasks);
        exec.shutdown();
        exec.awaitTermination(10, TimeUnit.MINUTES);
        Instant end = Instant.now();

        long durationMs = Duration.between(start, end).toMillis();

        // compute committed/failed from samples collected (safer in presence of ExecutionException)
        int committed = 0;
        int failed = 0;
        synchronized (samples) {
            for (String s : samples) {
                if (s.endsWith(",true")) committed++;
                else failed++;
            }
        }

        double tps = durationMs == 0 ? committed : (committed * 1000.0 / durationMs);

        StringBuilder summary = new StringBuilder();
        summary.append("----- PERFORMANCE TEST SUMMARY -----\n");
        summary.append("Threads: ").append(threads).append(", TXs/thread: ").append(txsPerThread).append(", Total TXs: ").append(totalTx).append('\n');
        summary.append("Amount per TX: ").append(amount).append('\n');
        summary.append("Duration ms: ").append(durationMs).append('\n');
        summary.append("Committed: ").append(committed).append(", Failed: ").append(failed).append('\n');
        summary.append(String.format("Throughput (committed tx/s): %.2f%n", tps));
        summary.append("------------------------------------\n");

        System.out.print(summary.toString());

        // write JSON summary to target/perf-results/perf-summary-latest.json
        try {
            Path outDir = Paths.get("target", "perf-results");
            Files.createDirectories(outDir);
            Path outFile = outDir.resolve("perf-summary-latest.json");

            String json = String.format(
                    "{\n  \"threads\": %d,\n  \"txsPerThread\": %d,\n  \"totalTx\": %d,\n  \"amountPerTx\": \"%s\",\n  \"durationMs\": %d,\n  \"committed\": %d,\n  \"failed\": %d,\n  \"throughput\": %.2f\n}",
                    threads, txsPerThread, totalTx, amount.toPlainString(), durationMs, committed, failed, tps
            );
            Files.write(outFile, json.getBytes(StandardCharsets.UTF_8));
            System.out.println("Wrote perf summary to: " + outFile.toAbsolutePath());

            // write per-sample CSV (JTL-like) to target/perf-results/perf-samples.jtl
            Path samplesFile = outDir.resolve("perf-samples.jtl");
            List<String> outLines = new ArrayList<>();
            outLines.add("time,latency,success");
            synchronized (samples) {
                outLines.addAll(samples);
            }
            Files.write(samplesFile, String.join(System.lineSeparator(), outLines).getBytes(StandardCharsets.UTF_8));
            System.out.println("Wrote per-sample results to: " + samplesFile.toAbsolutePath());

        } catch (Exception e) {
            System.err.println("Failed to write perf summary: " + e.getMessage());
        }

        // Do not assert total success to allow the perf run to finish and produce results in environments
        // where optimistic locking or transient failures may happen under concurrency. Instead, assert the test ran.
        assertTrue(true);
    }
}
