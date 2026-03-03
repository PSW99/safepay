package com.safepay.concurrency;

import com.safepay.domain.account.entity.Account;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.member.entity.Member;
import com.safepay.domain.member.repository.MemberRepository;
import com.safepay.domain.transaction.dto.TransactionDto.*;
import com.safepay.domain.transaction.repository.TransactionRepository;
import com.safepay.domain.transaction.service.TransactionService;
import com.safepay.global.util.AesEncryptor;
import com.safepay.integration.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시성 테스트 — 비관적 락(SELECT ... FOR UPDATE)으로 잔액 정합성 보장 검증
 */
@DisplayName("Transaction 동시성 테스트")
class TransactionConcurrencyTest extends ConcurrencyTestBase {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AesEncryptor aesEncryptor;

    private Long accountId;
    private Long memberId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        memberRepository.deleteAll();

        // 회원 생성
        Member member = Member.builder()
                .email("concurrency@safepay.com")
                .password(passwordEncoder.encode("password123"))
                .name("동시성테스트")
                .phone(aesEncryptor.encrypt("010-0000-0000"))
                .build();
        member = memberRepository.save(member);
        memberId = member.getId();

        // 계좌 생성 (잔액 10,000원)
        Account account = Account.builder()
                .member(member)
                .accountNumber(aesEncryptor.encrypt("100-01-000000-0"))
                .accountType(Account.AccountType.CHECKING)
                .build();
        account = accountRepository.save(account);
        accountId = account.getId();

        // 초기 입금 10,000원
        transactionService.deposit(accountId, memberId,
                new DepositRequest(new BigDecimal("10000"), "초기 입금"),
                UUID.randomUUID().toString());
    }

    @Test
    @DisplayName("동일 계좌에 10개 스레드가 동시 출금하면 잔액 정합성이 보장된다")
    void concurrentWithdraw_balanceConsistency() throws Exception {
        // Given: 잔액 10,000원, 10개 스레드가 각 1,000원 출금
        int threadCount = 10;
        BigDecimal withdrawAmount = new BigDecimal("1000");
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // When: 10개 스레드가 동시에 1,000원씩 출금
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    transactionService.withdraw(
                            accountId, memberId,
                            new WithdrawRequest(withdrawAmount, "동시 출금 " + idx),
                            UUID.randomUUID().toString()
                    );
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: 잔액은 정확히 0원이어야 함 (10,000 - 1,000 * 10 = 0)
        Account result = accountRepository.findById(accountId).orElseThrow();
        assertThat(result.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(successCount.get()).isEqualTo(10);
        assertThat(failCount.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("잔액 초과 동시 출금 시 일부만 성공하고 잔액은 음수가 되지 않는다")
    void concurrentWithdraw_insufficientBalance_noOverdraft() throws Exception {
        // Given: 잔액 5,000원, 10개 스레드가 각 1,000원 출금
        // → 5개만 성공, 5개는 잔액 부족
        int threadCount = 10;
        BigDecimal withdrawAmount = new BigDecimal("1000");

        // 먼저 5,000원 출금하여 잔액 5,000원으로 만들기
        transactionService.withdraw(accountId, memberId,
                new WithdrawRequest(new BigDecimal("5000"), "잔액 조정"),
                UUID.randomUUID().toString());

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // When
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    transactionService.withdraw(
                            accountId, memberId,
                            new WithdrawRequest(withdrawAmount, "동시 출금 " + idx),
                            UUID.randomUUID().toString()
                    );
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: 잔액은 0 이상이어야 하고, 성공 5개 + 실패 5개
        Account result = accountRepository.findById(accountId).orElseThrow();
        assertThat(result.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(successCount.get()).isEqualTo(5);
        assertThat(failCount.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("동시 입금 시 모든 금액이 정확히 반영된다")
    void concurrentDeposit_allAmountsReflected() throws Exception {
        // Given: 잔액 10,000원, 10개 스레드가 각 1,000원 입금
        int threadCount = 10;
        BigDecimal depositAmount = new BigDecimal("1000");
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        // When
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    transactionService.deposit(
                            accountId, memberId,
                            new DepositRequest(depositAmount, "동시 입금 " + idx),
                            UUID.randomUUID().toString()
                    );
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // 입금은 실패하지 않아야 함
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: 잔액은 정확히 20,000원 (10,000 + 1,000 * 10)
        Account result = accountRepository.findById(accountId).orElseThrow();
        assertThat(result.getBalance()).isEqualByComparingTo(new BigDecimal("20000"));
        assertThat(successCount.get()).isEqualTo(10);
    }

    @Test
    @DisplayName("동시 입금 + 출금 혼합 시 잔액 정합성이 보장된다")
    void concurrentMixed_balanceConsistency() throws Exception {
        // Given: 잔액 10,000원
        // 5개 스레드: 각 1,000원 입금 (+5,000)
        // 5개 스레드: 각 1,000원 출금 (-5,000)
        // 결과: 10,000원 유지
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        // When
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    if (idx < 5) {
                        // 입금
                        transactionService.deposit(
                                accountId, memberId,
                                new DepositRequest(new BigDecimal("1000"), "혼합 입금 " + idx),
                                UUID.randomUUID().toString()
                        );
                    } else {
                        // 출금
                        transactionService.withdraw(
                                accountId, memberId,
                                new WithdrawRequest(new BigDecimal("1000"), "혼합 출금 " + idx),
                                UUID.randomUUID().toString()
                        );
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // ignore
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Then: 잔액은 정확히 10,000원 (입금 5,000 - 출금 5,000 = 변동 없음)
        Account result = accountRepository.findById(accountId).orElseThrow();
        assertThat(result.getBalance()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(successCount.get()).isEqualTo(10);
    }
}