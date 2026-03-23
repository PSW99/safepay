package com.safepay.domain.account.service;

import com.safepay.domain.account.dto.AccountDto.*;
import com.safepay.domain.account.entity.Account;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.member.entity.Member;
import com.safepay.domain.member.repository.MemberRepository;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import com.safepay.global.util.AccountNumberGenerator;
import com.safepay.global.util.AesEncryptor;
import com.safepay.global.util.HmacUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final MemberRepository memberRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final AesEncryptor aesEncryptor;
    private final HmacUtil hmacUtil;

    @Transactional
    public CreateResponse createAccount(Long memberId, CreateRequest request) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_INVALID_CREDENTIALS));

        // 계좌번호 생성 + 암호화 + 블라인드 인덱스
        String[] result = generateUniqueAccountNumber(request.getAccountType().name());
        String accountNumber = result[0];     // 평문 (응답용)
        String encryptedNumber = result[1];   // AES-GCM 암호문 (저장용 — 복호화 가능)
        String accountNumberHash = result[2]; // HMAC-SHA-256 해시 (유일성 검증용)

        Account account = Account.builder()
                .member(member)
                .accountNumber(encryptedNumber)
                .accountNumberHash(accountNumberHash)
                .accountType(request.getAccountType())
                .build();

        try {
            Account saved = accountRepository.saveAndFlush(account);
            log.info("계좌 개설 완료: accountId={}, memberId={}", saved.getId(), memberId);
            return new CreateResponse(saved.getId(), accountNumber);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> getMyAccounts(Long memberId) {
        return accountRepository.findByMemberId(memberId).stream()
                .map(account -> AccountResponse.from(
                        account,
                        aesEncryptor.decrypt(account.getAccountNumber())
                ))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(Long accountId, Long memberId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

        if (!account.isOwnedBy(memberId)) {
            throw new CustomException(ErrorCode.ACCOUNT_NOT_OWNER);
        }

        return AccountResponse.from(account, aesEncryptor.decrypt(account.getAccountNumber()));
    }

    /**
     * 유일한 계좌번호를 생성한다.
     *
     * @return [0] 평문 계좌번호, [1] AES 암호문, [2] HMAC 해시
     */
    private String[] generateUniqueAccountNumber(String accountType) {
        int attempts = 0;
        while (true) {
            String accountNumber = accountNumberGenerator.generate(accountType);
            String encrypted = aesEncryptor.encrypt(accountNumber);
            String hash = hmacUtil.hash(accountNumber);

            if (!accountRepository.existsByAccountNumberHash(hash)) {
                return new String[]{accountNumber, encrypted, hash};
            }
            if (++attempts >= 10) {
                throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
            }
        }
    }
}