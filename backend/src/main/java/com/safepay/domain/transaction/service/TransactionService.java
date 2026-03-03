package com.safepay.domain.transaction.service;

import com.safepay.domain.account.entity.Account;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.transaction.dto.TransactionDto.DepositRequest;
import com.safepay.domain.transaction.dto.TransactionDto.TransactionResponse;
import com.safepay.domain.transaction.entity.Transaction;
import com.safepay.domain.transaction.repository.TransactionRepository;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        transactionRepository.save(tx);

        log.info("입금 완료: accountId={}, amount={}, balanceAfter={}",
                accountId, request.getAmount(), account.getBalance());

        return TransactionResponse.from(tx);
    }
}
