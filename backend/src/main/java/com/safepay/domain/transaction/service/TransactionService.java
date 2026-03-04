package com.safepay.domain.transaction.service;

import com.safepay.domain.account.entity.Account;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.transaction.dto.TransactionDto;
import com.safepay.domain.transaction.dto.TransactionDto.DepositRequest;
import com.safepay.domain.transaction.dto.TransactionDto.TransactionResponse;
import com.safepay.domain.transaction.entity.Transaction;
import com.safepay.domain.transaction.repository.TransactionRepository;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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

    // 입금 처리
    @Transactional
    public TransactionResponse deposit(Long accountId, Long memberId,
                                       DepositRequest request, String idempotencyKey) {
        // 멱등성 체크
        TransactionResponse existing = checkIdempotency(accountId, idempotencyKey);
        if (existing != null) {
            log.info("중복 입금 요청 감지: idempotencyKey={}", idempotencyKey);
            return existing;
        }

        // 비관적 락으로 계좌 조회
        Account account = accountRepository.findByIdWithLock(accountId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

        // 소유자 검증
        if (!account.isOwnedBy(memberId)) {
            throw new CustomException(ErrorCode.ACCOUNT_NOT_OWNER);
        }

        // 입금 (도메인 로직 — Account.deposit())
        account.deposit(request.getAmount());

        // 거래 내역 저장
        Transaction tx = Transaction.createDeposit(
                account, request.getAmount(), request.getDescription(), idempotencyKey);
        try {
            transactionRepository.save(tx);
        } catch (DataIntegrityViolationException e) {
            log.warn("멱등성 키 중복 (동시 요청): idempotencyKey={}", idempotencyKey);
            return transactionRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey)
                    .map(TransactionResponse::from)
                    .orElseThrow(() -> new CustomException(ErrorCode.DUPLICATE_TRANSACTION));
        }

        log.info("입금 완료: accountId={}, amount={}, balanceAfter={}",
                accountId, request.getAmount(), account.getBalance());

        return TransactionResponse.from(tx);
    }

    // 출금 처리
    @Transactional
    public TransactionResponse withdraw(Long accountId, Long memberId,
                                        TransactionDto.WithdrawRequest request, String idempotencyKey) {
        // 멱등성 체크
        TransactionResponse existing = checkIdempotency(accountId, idempotencyKey);
        if (existing != null) {
            log.info("중복 출금 요청 감지: idempotencyKey={}", idempotencyKey);
            return existing;
        }

        // 비관적 락으로 계좌 조회
        Account account = accountRepository.findByIdWithLock(accountId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

        // 소유자 검증
        if (!account.isOwnedBy(memberId)) {
            throw new CustomException(ErrorCode.ACCOUNT_NOT_OWNER);
        }

        // 출금 (도메인 로직 — 잔액 부족 시 CustomException 발생)
        account.withdraw(request.getAmount());

        // 거래 내역 저장
        Transaction tx = Transaction.createWithdraw(
                account, request.getAmount(), request.getDescription(), idempotencyKey);
        try {
            transactionRepository.save(tx);
        } catch (DataIntegrityViolationException e) {
            log.warn("멱등성 키 중복 (동시 요청): idempotencyKey={}", idempotencyKey);
            return transactionRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey)
                    .map(TransactionResponse::from)
                    .orElseThrow(() -> new CustomException(ErrorCode.DUPLICATE_TRANSACTION));
        }

        log.info("출금 완료: accountId={}, amount={}, balanceAfter={}",
                accountId, request.getAmount(), account.getBalance());

        return TransactionResponse.from(tx);
    }

    // 거래 내역 조회 (페이징)
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

    // Private
    private TransactionResponse checkIdempotency(Long accountId, String idempotencyKey) {
        return transactionRepository.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey)
                .map(TransactionResponse::from)
                .orElse(null);
    }
}
