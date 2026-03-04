package com.safepay.domain.transaction.repository;

import com.safepay.domain.transaction.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Page<Transaction> findByAccountIdOrderByCreatedAtDesc(Long accountId, Pageable pageable);

    Optional<Transaction> findByAccountIdAndIdempotencyKey(Long accountId, String idempotencyKey);
}
