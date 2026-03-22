package com.safepay.domain.transaction.service;

import com.safepay.domain.account.entity.Account;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.transaction.dto.TransactionDto.*;
import com.safepay.domain.transaction.repository.TransactionRepository;
import com.safepay.domain.transaction.dto.TransferDto;
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

    // 거래 내역 조회 (페이징) — 분산 락 불필요 (읽기 전용)
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

    public TransferDto.TransferResponse transfer(Long fromAccountId, Long toAccountId,
                                                 Long memberId, TransferDto.TransferRequest request,
                                                 String idempotencyKey) {
        // 자기 자신 송금 방지 (락 획득 전에 빠르게 검증)
        if (fromAccountId.equals(toAccountId)) {
            throw new CustomException(ErrorCode.SELF_TRANSFER);
        }

        // 멱등성 체크 (출금 계좌 기준)
        TransactionResponse existing = checkIdempotency(fromAccountId, idempotencyKey);
        if (existing != null) {
            log.info("중복 송금 요청 감지: idempotencyKey={}", idempotencyKey);
            // 멱등성 반환은 TransferResponse가 아닌 기존 거래 결과
            // 이미 성공한 송금의 out 거래를 기반으로 응답 구성
            return buildTransferResponseFromExisting(existing, fromAccountId, toAccountId);
        }

        // 분산 락 획득 — 두 계좌 모두 ID 오름차순
        Long firstLockId = Math.min(fromAccountId, toAccountId);
        Long secondLockId = Math.max(fromAccountId, toAccountId);

        RLock lock1 = distributedLockManager.tryLockOrNull(firstLockId);
        RLock lock2 = distributedLockManager.tryLockOrNull(secondLockId);
        try {
            return transactionExecutor.executeTransfer(
                    fromAccountId, toAccountId, memberId, request, idempotencyKey);
        } finally {
            // 해제는 획득의 역순
            distributedLockManager.unlock(lock2);
            distributedLockManager.unlock(lock1);
        }
    }


    // ─── Private ───
    private TransactionResponse checkIdempotency(Long accountId, String idempotencyKey) {
        return transactionRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey)
                .map(TransactionResponse::from)
                .orElse(null);
    }

    private TransferDto.TransferResponse buildTransferResponseFromExisting(
            TransactionResponse outTx, Long fromAccountId, Long toAccountId) {
        // 기존 TRANSFER_OUT 거래 정보로 응답 구성
        return new TransferDto.TransferResponse(
                outTx.getTransactionId(), null, // toTransactionId는 조회 불가
                outTx.getAmount(),
                outTx.getBalanceAfter(), null, // toBalanceAfter는 조회 불가
                outTx.getStatus(),
                outTx.getCreatedAt());
    }
}
