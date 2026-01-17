package org.pilot.transactionservicepilot.repository;

import org.pilot.transactionservicepilot.entity.TransactionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<TransactionRecord, Long> {
    Optional<TransactionRecord> findByTxId(String txId);

    // find transactions with any of the given statuses (PENDING, FAILED, etc.)
    List<TransactionRecord> findByStatusIn(List<String> statuses);
}
