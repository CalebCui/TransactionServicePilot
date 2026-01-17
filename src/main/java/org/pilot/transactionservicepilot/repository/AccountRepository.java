package org.pilot.transactionservicepilot.repository;

import org.pilot.transactionservicepilot.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByAccountNumber(String accountNumber);

    @Modifying
    @Transactional
    @Query(value = "UPDATE accounts SET balance = balance - ?2, available_balance = available_balance - ?2, updated_at = CURRENT_TIMESTAMP WHERE id = ?1 AND available_balance >= ?2", nativeQuery = true)
    int debitIfAvailable(Long accountId, BigDecimal amount);

    @Modifying
    @Transactional
    @Query(value = "UPDATE accounts SET balance = balance + ?2, updated_at = CURRENT_TIMESTAMP WHERE id = ?1", nativeQuery = true)
    int credit(Long accountId, BigDecimal amount);
}
