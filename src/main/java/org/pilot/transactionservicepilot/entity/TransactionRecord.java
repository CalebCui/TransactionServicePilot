package org.pilot.transactionservicepilot.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
public class TransactionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tx_id", unique = true, nullable = false)
    private String txId;

    @Column(name = "account_id")
    private Long accountId; // legacy

    @Column(name = "source_account_id")
    private Long sourceAccountId;

    @Column(name = "destination_account_id")
    private Long destinationAccountId;

    @Column(nullable = false)
    private String type; // DEBIT or CREDIT or TRANSFER

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private String status; // PENDING, COMMITTED, FAILED

    private Instant createdAt = Instant.now();

    private Instant processedAt;

    @Column(columnDefinition = "TEXT")
    private String error;

    private Integer retryCount = 0;

    private Instant timestamp;

}
