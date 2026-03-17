package com.safepay.domain.transaction.service;

import com.safepay.domain.account.entity.Account;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.transaction.dto.TransactionDto.*;
import com.safepay.domain.transaction.repository.TransactionRepository;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import com.safepay.global.util.DistributedLockManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final DistributedLockManager distributedLockManager;
    private final TransactionExecutor transactionExecutor;

    // 입금 처리
    public TransactionResponse deposit(Long accountId, Long memberId,
                                       DepositRequest request, String idempotencyKey) {
        // 멱등성 체크 (락 바깥에서 — 이미 처리된 요청은 빠르게 반환)
        TransactionResponse existing = checkIdempotency(accountId, idempotencyKey);
        if (existing != null) {
            log.info("중복 입금 요청 감지: idempotencyKey={}", idempotencyKey);
            return existing;
        }

        // 분산 락 획득 (Redis 장애 시 null → 비관적 락만으로 진행)
        RLock lock = distributedLockManager.tryLockOrNull(accountId);
        try {
            return transactionExecutor.executeDeposit(accountId, memberId, request, idempotencyKey);
        } finally {
            distributedLockManager.unlock(lock);
        }
    }

    // 출금 처리
    public TransactionResponse withdraw(Long accountId, Long memberId,
                                        WithdrawRequest request, String idempotencyKey) {
        // 멱등성 체크
        TransactionResponse existing = checkIdempotency(accountId, idempotencyKey);
        if (existing != null) {
            log.info("중복 출금 요청 감지: idempotencyKey={}", idempotencyKey);
            return existing;
        }

        // 분산 락 획득
        RLock lock = distributedLockManager.tryLockOrNull(accountId);
        try {
            return transactionExecutor.executeWithdraw(accountId, memberId, request, idempotencyKey);
        } finally {
            distributedLockManager.unlock(lock);
        }
    }

    //거래 내역 조회 (페이징) — 분산 락 불필요 (읽기 전용)
    @Transactional(readOnly = true)
    public Page<TransactionResponse> getTransactions(Long accountId, Long memberId,
                                                     Pageable pageable) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

        if (!account.isOwnedBy(memberId)) {
            throw new CustomException(ErrorCode.ACCOUNT_NOT_OWNER);
        }

        return transactionRepository
                .findByAccountIdOrderByCreatedAtDesc(accountId, pageable)
                .map(TransactionResponse::from);
    }

    // ─── Private ───
    private TransactionResponse checkIdempotency(Long accountId, String idempotencyKey) {
        return transactionRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey)
                .map(TransactionResponse::from)
                .orElse(null);
    }
}
