package com.safepay.domain.transaction.repository;

import com.safepay.domain.transaction.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;


public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}
