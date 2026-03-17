package com.safepay.domain.transaction.service;

import com.safepay.domain.account.entity.Account;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.transaction.dto.TransactionDto.*;
import com.safepay.domain.transaction.entity.Transaction;
import com.safepay.domain.transaction.repository.TransactionRepository;
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
}
