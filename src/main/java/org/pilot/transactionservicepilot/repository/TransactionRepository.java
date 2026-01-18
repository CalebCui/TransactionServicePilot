package org.pilot.transactionservicepilot.repository;

import org.pilot.transactionservicepilot.entity.TransactionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<TransactionRecord, Long> {
    Optional<TransactionRecord> findByTxId(String txId);

    // find transactions with any of the given statuses (PENDING, FAILED, etc.)
    List<TransactionRecord> findByStatusIn(List<String> statuses);

    @Query("select t from TransactionRecord t where t.status in :statuses and (t.nextAttemptAt is null or t.nextAttemptAt <= :now) order by t.createdAt asc")
    List<TransactionRecord> findRetryable(@Param("statuses") List<String> statuses, @Param("now") Instant now);
}
