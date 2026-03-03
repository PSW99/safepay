package com.safepay.domain.account.repository;

import com.safepay.domain.account.entity.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * 비관적 락으로 계좌 조회 (SELECT ... FOR UPDATE)
     * ADR-001: 입출금 동시성 제어를 위해 사용
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdWithLock(@Param("id") Long id);

    List<Account> findByMemberId(Long memberId);

    boolean existsByAccountNumber(String accountNumber);
}
