package com.safepay.domain.transaction.service;

import com.safepay.domain.account.entity.Account;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.transaction.dto.TransactionDto.*;
import com.safepay.domain.transaction.entity.Transaction;
import com.safepay.domain.transaction.repository.TransactionRepository;
import com.safepay.domain.transaction.dto.TransferDto;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionExecutor {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    @Transactional
    public TransactionResponse executeDeposit(Long accountId, Long memberId,
                                              DepositRequest request, String idempotencyKey) {
        Account account = accountRepository.findByIdWithLock(accountId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

        if (!account.isOwnedBy(memberId)) {
            throw new CustomException(ErrorCode.ACCOUNT_NOT_OWNER);
        }

        account.deposit(request.getAmount());

        Transaction tx = Transaction.createDeposit(
                account, request.getAmount(), request.getDescription(), idempotencyKey);
        try {
            transactionRepository.save(tx);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.DUPLICATE_TRANSACTION);
        }

        log.info("입금 완료: accountId={}, amount={}, balanceAfter={}",
                accountId, request.getAmount(), account.getBalance());

        return TransactionResponse.from(tx);
    }

    @Transactional
    public TransactionResponse executeWithdraw(Long accountId, Long memberId,
                                               WithdrawRequest request, String idempotencyKey) {
        Account account = accountRepository.findByIdWithLock(accountId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

        if (!account.isOwnedBy(memberId)) {
            throw new CustomException(ErrorCode.ACCOUNT_NOT_OWNER);
        }

        account.withdraw(request.getAmount());

        Transaction tx = Transaction.createWithdraw(
                account, request.getAmount(), request.getDescription(), idempotencyKey);
        try {
            transactionRepository.save(tx);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.DUPLICATE_TRANSACTION);
        }

        log.info("출금 완료: accountId={}, amount={}, balanceAfter={}",
                accountId, request.getAmount(), account.getBalance());

        return TransactionResponse.from(tx);
    }

    @Transactional
    public TransferDto.TransferResponse executeTransfer(Long fromAccountId, Long toAccountId,
                                                        Long memberId, TransferDto.TransferRequest request,
                                                        String idempotencyKey) {
        // 자기 자신 송금 방지
        if (fromAccountId.equals(toAccountId)) {
            throw new CustomException(ErrorCode.SELF_TRANSFER);
        }

        // 데드락 방지: 계좌 ID 오름차순으로 비관적 락 획득
        Long firstLockId = Math.min(fromAccountId, toAccountId);
        Long secondLockId = Math.max(fromAccountId, toAccountId);

        Account firstAccount = accountRepository.findByIdWithLock(firstLockId)
                .orElseThrow(() -> new CustomException(
                        firstLockId.equals(fromAccountId) ? ErrorCode.ACCOUNT_NOT_FOUND : ErrorCode.TRANSFER_ACCOUNT_NOT_FOUND));
        Account secondAccount = accountRepository.findByIdWithLock(secondLockId)
                .orElseThrow(() -> new CustomException(
                        secondLockId.equals(fromAccountId) ? ErrorCode.ACCOUNT_NOT_FOUND : ErrorCode.TRANSFER_ACCOUNT_NOT_FOUND));

        // from/to 매핑 (락 순서와 무관하게 정확한 계좌 매핑)
        Account fromAccount = fromAccountId.equals(firstLockId) ? firstAccount : secondAccount;
        Account toAccount = toAccountId.equals(firstLockId) ? firstAccount : secondAccount;

        // 소유자 검증 (출금 계좌만)
        if (!fromAccount.isOwnedBy(memberId)) {
            throw new CustomException(ErrorCode.ACCOUNT_NOT_OWNER);
        }

        // 출금 → 입금 (도메인 로직)
        fromAccount.withdraw(request.getAmount());
        toAccount.deposit(request.getAmount());

        // 거래 내역 2건 저장
        String description = request.getDescription() != null
                ? request.getDescription()
                : "송금";

        Transaction outTx = Transaction.createTransferOut(
                fromAccount, request.getAmount(), description, idempotencyKey);
        Transaction inTx = Transaction.createTransferIn(
                toAccount, request.getAmount(), description, idempotencyKey);

        try {
            transactionRepository.save(outTx);
            transactionRepository.save(inTx);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.DUPLICATE_TRANSACTION);
        }

        log.info("송금 완료: from={}, to={}, amount={}, fromBalance={}, toBalance={}",
                fromAccountId, toAccountId, request.getAmount(),
                fromAccount.getBalance(), toAccount.getBalance());

        return new TransferDto.TransferResponse(
                outTx.getId(), inTx.getId(),
                request.getAmount(),
                fromAccount.getBalance(), toAccount.getBalance(),
                "SUCCESS", outTx.getCreatedAt());
    }
}
