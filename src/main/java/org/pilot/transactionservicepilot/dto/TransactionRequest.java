package org.pilot.transactionservicepilot.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
public class TransactionRequest {
    private String txId;
    private Long accountId; // legacy single-account operations
    private Long sourceAccountId;
    private Long destinationAccountId;
    private String type; // DEBIT or CREDIT or TRANSFER
    private BigDecimal amount;
    private String currency;
    private Instant timestamp;
}
