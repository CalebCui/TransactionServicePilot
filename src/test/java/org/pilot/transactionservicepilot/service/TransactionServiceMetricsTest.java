package org.pilot.transactionservicepilot.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.pilot.transactionservicepilot.entity.TransactionRecord;
import org.pilot.transactionservicepilot.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {"app.sync.base-backoff-seconds=1","app.sync.max-retries=3"})
class TransactionServiceMetricsTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    TransactionService transactionService;

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    void permanentFailureIncrementsMetric() {
        // determine the actual MeterRegistry used by the TransactionService bean (reflection),
        // fall back to the test-provided autowired registry if necessary. This ensures we read
        // the same registry that the service increments during the test.
        MeterRegistry actualRegistry = null;
        try {
            java.lang.reflect.Field mrField = TransactionService.class.getDeclaredField("meterRegistry");
            mrField.setAccessible(true);
            Object val = mrField.get(transactionService);
            if (val instanceof MeterRegistry) actualRegistry = (MeterRegistry) val;
        } catch (Exception ignored) {
        }
        if (actualRegistry == null) actualRegistry = meterRegistry;

        double baseline = 0.0;
        Counter baseCounter = actualRegistry.find("transaction.permanentFailure.count").tag("type", "DEBIT").counter();
        if (baseCounter != null) baseline = baseCounter.count();

        // ensure no other pending transactions exist so only the record we insert is processed
        transactionRepository.deleteAll();

        String txId = "tx-metric-" + UUID.randomUUID();
        TransactionRecord rec = new TransactionRecord();
        rec.setTxId(txId);
        rec.setAccountId(999999L); // non-existent account
        rec.setType("DEBIT");
        rec.setAmount(new BigDecimal("1.00"));
        rec.setCurrency("CNY");
        rec.setStatus("PENDING");
        rec.setRetryCount(0);
        rec.setCreatedAt(Instant.now());
        transactionRepository.save(rec);

        // Run reprocess up to max retries; after each attempt, ensure nextAttemptAt is in the past to allow immediate retry
        for (int i = 0; i < 3; i++) {
            transactionService.reprocessPending();
            TransactionRecord r = transactionRepository.findByTxId(txId).orElseThrow();
            // set nextAttemptAt to past so next iteration will retry immediately
            r.setNextAttemptAt(Instant.now().minusSeconds(1));
            transactionRepository.save(r);
        }

        TransactionRecord finalRec = transactionRepository.findByTxId(txId).orElseThrow();
        assertEquals("FAILED", finalRec.getStatus());

        // read the counter from the test-provided SimpleMeterRegistry and assert the delta is 1
        Counter after = actualRegistry.find("transaction.permanentFailure.count").tag("type", "DEBIT").counter();
        double afterCount = after == null ? 0.0 : after.count();
        assertEquals(1.0, afterCount - baseline, 0.0001, "Delta permanentFailure counter on service MeterRegistry should be 1");
    }
}
