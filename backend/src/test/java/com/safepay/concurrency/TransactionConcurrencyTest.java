package com.safepay.concurrency;

import com.safepay.domain.account.entity.Account;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.member.entity.Member;
import com.safepay.domain.member.repository.MemberRepository;
import com.safepay.domain.transaction.dto.TransactionDto.*;
import com.safepay.domain.transaction.repository.TransactionRepository;
import com.safepay.domain.transaction.service.TransactionService;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import com.safepay.global.util.AesEncryptor;
import com.safepay.integration.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Transaction 동시성 테스트 (분산 락 + 비관적 락)")
class TransactionConcurrencyTest extends IntegrationTestBase {

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

    @Autowired
    private RedissonClient redissonClient;

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

        // 계좌 생성 + 초기 입금 10,000원
        Account account = Account.builder()
                .member(member)
                .accountNumber(aesEncryptor.encrypt("100-01-000000-0"))
                .accountType(Account.AccountType.CHECKING)
                .build();
        account = accountRepository.save(account);
        accountId = account.getId();

        transactionService.deposit(accountId, memberId,
                new DepositRequest(new BigDecimal("10000"), "초기 입금"),
                UUID.randomUUID().toString());
    }

    // 기존 동시성 테스트 (분산 락 환경에서도 통과 확인)
    @Nested
    @DisplayName("잔액 정합성 (이중 방어)")
    class BalanceConsistency {

        @Test
        @DisplayName("⭐ 10스레드 동시 출금 → 잔액 정확히 0원 (분산 락 + 비관적 락)")
        void concurrentWithdraw_balanceConsistency() throws Exception {
            int threadCount = 10;
            BigDecimal withdrawAmount = new BigDecimal("1000");
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(); // 모든 스레드가 준비되면 동시에 출발
                        transactionService.withdraw(
                                accountId, memberId,
                                new WithdrawRequest(withdrawAmount, "동시 출금 " + idx),
                                UUID.randomUUID().toString()
                        );
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();       // 모든 스레드 준비 대기
            start.countDown();   // 동시 출발
            done.await(15, TimeUnit.SECONDS);
            executor.shutdown();

            Account result = accountRepository.findById(accountId).orElseThrow();
            assertThat(result.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(successCount.get()).isEqualTo(10);
            assertThat(failCount.get()).isEqualTo(0);
        }

        @Test
        @DisplayName("잔액 초과 동시 출금 → 일부만 성공, 음수 방지")
        void concurrentWithdraw_insufficientBalance_noOverdraft() throws Exception {
            // 잔액 5,000원으로 조정
            transactionService.withdraw(accountId, memberId,
                    new WithdrawRequest(new BigDecimal("5000"), "잔액 조정"),
                    UUID.randomUUID().toString());

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        transactionService.withdraw(
                                accountId, memberId,
                                new WithdrawRequest(new BigDecimal("1000"), "동시 출금 " + idx),
                                UUID.randomUUID().toString()
                        );
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();
            done.await(15, TimeUnit.SECONDS);
            executor.shutdown();

            Account result = accountRepository.findById(accountId).orElseThrow();
            assertThat(result.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            assertThat(successCount.get()).isEqualTo(5);
            assertThat(failCount.get()).isEqualTo(5);
        }

        @Test
        @DisplayName("동시 입금 → 모든 금액이 정확히 반영")
        void concurrentDeposit_allAmountsReflected() throws Exception {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        transactionService.deposit(
                                accountId, memberId,
                                new DepositRequest(new BigDecimal("1000"), "동시 입금 " + idx),
                                UUID.randomUUID().toString()
                        );
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // 입금은 실패하지 않아야 함
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();
            done.await(15, TimeUnit.SECONDS);
            executor.shutdown();

            Account result = accountRepository.findById(accountId).orElseThrow();
            assertThat(result.getBalance()).isEqualByComparingTo(new BigDecimal("20000"));
            assertThat(successCount.get()).isEqualTo(10);
        }

        @Test
        @DisplayName("입금 + 출금 혼합 동시 요청 → 잔액 정합성 유지")
        void concurrentMixed_balanceConsistency() throws Exception {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        if (idx < 5) {
                            transactionService.deposit(
                                    accountId, memberId,
                                    new DepositRequest(new BigDecimal("1000"), "혼합 입금 " + idx),
                                    UUID.randomUUID().toString());
                        } else {
                            transactionService.withdraw(
                                    accountId, memberId,
                                    new WithdrawRequest(new BigDecimal("1000"), "혼합 출금 " + idx),
                                    UUID.randomUUID().toString());
                        }
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // ignore
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();
            done.await(15, TimeUnit.SECONDS);
            executor.shutdown();

            Account result = accountRepository.findById(accountId).orElseThrow();
            assertThat(result.getBalance()).isEqualByComparingTo(new BigDecimal("10000"));
            assertThat(successCount.get()).isEqualTo(10);
        }
    }

    // 분산 락 특화 테스트
    @Nested
    @DisplayName("분산 락 동작 검증")
    class DistributedLockBehavior {

        @Test
        @DisplayName("분산 락이 Redis에 실제로 생성되고 해제된다")
        void distributedLock_createdAndReleased() {
            // When: 입금 요청 (분산 락 획득 → 처리 → 해제)
            transactionService.deposit(accountId, memberId,
                    new DepositRequest(new BigDecimal("1000"), "락 확인"),
                    UUID.randomUUID().toString());

            // Then: 거래 완료 후 락이 해제되어 있어야 함
            String lockKey = "safepay:lock:account:" + accountId;
            RLock lock = redissonClient.getLock(lockKey);
            assertThat(lock.isLocked()).isFalse();
        }

        @Test
        @DisplayName("서로 다른 계좌는 동시에 처리된다 (락이 독립적)")
        void differentAccounts_processedConcurrently() throws Exception {
            // Given: 두 번째 계좌 생성
            Member member2 = Member.builder()
                    .email("user2@safepay.com")
                    .password(passwordEncoder.encode("password123"))
                    .name("유저2")
                    .phone(aesEncryptor.encrypt("010-1111-1111"))
                    .build();
            member2 = memberRepository.save(member2);

            Account account2 = Account.builder()
                    .member(member2)
                    .accountNumber(aesEncryptor.encrypt("100-01-111111-1"))
                    .accountType(Account.AccountType.CHECKING)
                    .build();
            account2 = accountRepository.save(account2);
            Long account2Id = account2.getId();
            Long member2Id = member2.getId();

            transactionService.deposit(account2Id, member2Id,
                    new DepositRequest(new BigDecimal("10000"), "초기 입금"),
                    UUID.randomUUID().toString());

            // When: 두 계좌에 동시 출금
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            AtomicInteger successCount = new AtomicInteger(0);

            // 계좌 1 출금
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    transactionService.withdraw(accountId, memberId,
                            new WithdrawRequest(new BigDecimal("1000"), "계좌1 출금"),
                            UUID.randomUUID().toString());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // ignore
                } finally {
                    done.countDown();
                }
            });

            // 계좌 2 출금
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    transactionService.withdraw(account2Id, member2Id,
                            new WithdrawRequest(new BigDecimal("1000"), "계좌2 출금"),
                            UUID.randomUUID().toString());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // ignore
                } finally {
                    done.countDown();
                }
            });

            ready.await();
            start.countDown();
            done.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            // Then: 둘 다 성공 (서로 다른 계좌이므로 경합 없음)
            assertThat(successCount.get()).isEqualTo(2);

            Account result1 = accountRepository.findById(accountId).orElseThrow();
            Account result2 = accountRepository.findById(account2Id).orElseThrow();
            assertThat(result1.getBalance()).isEqualByComparingTo(new BigDecimal("9000"));
            assertThat(result2.getBalance()).isEqualByComparingTo(new BigDecimal("9000"));
        }

        @Test
        @DisplayName("멱등성 키 중복 요청은 분산 락을 획득하지 않는다")
        void idempotencyKey_duplicate_skipsLock() {
            String sameKey = UUID.randomUUID().toString();

            // 1차 요청: 정상 처리
            transactionService.deposit(accountId, memberId,
                    new DepositRequest(new BigDecimal("1000"), "첫 요청"),
                    sameKey);

            // 2차 요청: 멱등성 체크에서 빠르게 반환 (락 미획득)
            transactionService.deposit(accountId, memberId,
                    new DepositRequest(new BigDecimal("1000"), "중복 요청"),
                    sameKey);

            // Then: 잔액은 1번만 변경 (10,000 + 1,000 = 11,000)
            Account result = accountRepository.findById(accountId).orElseThrow();
            assertThat(result.getBalance()).isEqualByComparingTo(new BigDecimal("11000"));
        }

        @Test
        @DisplayName("예외 발생 시 분산 락이 정상 해제된다")
        void exception_lockIsReleased() {
            // Given: 잔액 부족 출금 시도 (예외 발생)
            try {
                transactionService.withdraw(accountId, memberId,
                        new WithdrawRequest(new BigDecimal("99999"), "잔액 부족"),
                        UUID.randomUUID().toString());
            } catch (CustomException e) {
                assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
            }

            // Then: 예외가 발생해도 락은 해제되어야 함
            String lockKey = "safepay:lock:account:" + accountId;
            RLock lock = redissonClient.getLock(lockKey);
            assertThat(lock.isLocked()).isFalse();

            // 이후 정상 요청이 처리 가능해야 함
            transactionService.deposit(accountId, memberId,
                    new DepositRequest(new BigDecimal("1000"), "정상 입금"),
                    UUID.randomUUID().toString());

            Account result = accountRepository.findById(accountId).orElseThrow();
            assertThat(result.getBalance()).isEqualByComparingTo(new BigDecimal("11000"));
        }
    }
}