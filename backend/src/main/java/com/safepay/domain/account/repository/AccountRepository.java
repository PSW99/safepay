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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdWithLock(@Param("id") Long id);

    List<Account> findByMemberId(Long memberId);

    // 블라인드 인덱스로 계좌번호 중복 검사
    boolean existsByAccountNumberHash(String accountNumberHash);

    /**
     * @deprecated Phase 1 호환용. AES-GCM 랜덤 IV로 인해 정확한 중복 검사 불가.
     *             existsByAccountNumberHash()를 사용할 것.
     */
    @Deprecated
    boolean existsByAccountNumber(String accountNumber);
}