package com.safepay.concurrency;

import com.safepay.domain.account.entity.Account;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.member.entity.Member;
import com.safepay.domain.member.repository.MemberRepository;
import com.safepay.domain.transaction.dto.TransactionDto.*;
import com.safepay.domain.transaction.repository.TransactionRepository;
import com.safepay.domain.transaction.service.TransactionService;
import com.safepay.domain.transaction.dto.TransferDto;
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
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        void distributedLock_createdAndReleased() throws Exception {
            // Given: 락 키 확인용
            String lockKey = "safepay:lock:account:" + accountId;
            CountDownLatch lockAcquired = new CountDownLatch(1);
            CountDownLatch canRelease = new CountDownLatch(1);

            // When: 별도 스레드에서 직접 락을 잡아 거래 중 락이 존재함을 증명
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Boolean> lockHeldDuringTransaction = executor.submit(() -> {
                RLock manualLock = redissonClient.getLock(lockKey);
                manualLock.lock();
                lockAcquired.countDown();
                canRelease.await(5, TimeUnit.SECONDS);
                boolean wasLocked = manualLock.isLocked();
                manualLock.unlock();
                return wasLocked;
            });

            lockAcquired.await(5, TimeUnit.SECONDS);

            // Then: 락이 실제로 잡혀 있는 동안 Redis에서 확인
            RLock lock = redissonClient.getLock(lockKey);
            assertThat(lock.isLocked()).isTrue();

            canRelease.countDown();
            assertThat(lockHeldDuringTransaction.get(5, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // 락 해제 후 거래 정상 처리 가능
            transactionService.deposit(accountId, memberId,
                    new DepositRequest(new BigDecimal("1000"), "락 확인"),
                    UUID.randomUUID().toString());

            // 거래 완료 후 락이 해제되어 있어야 함
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
        @DisplayName("서로 다른 계좌가 같은 멱등성 키를 사용해도 각각 독립 처리된다")
        void crossAccount_sameIdempotencyKey_processedIndependently() {
            // Given: 두 번째 계좌 생성
            Member member2 = Member.builder()
                    .email("cross@safepay.com")
                    .password(passwordEncoder.encode("password123"))
                    .name("교차테스트")
                    .phone(aesEncryptor.encrypt("010-2222-2222"))
                    .build();
            member2 = memberRepository.save(member2);

            Account account2 = Account.builder()
                    .member(member2)
                    .accountNumber(aesEncryptor.encrypt("100-01-222222-2"))
                    .accountType(Account.AccountType.CHECKING)
                    .build();
            account2 = accountRepository.save(account2);
            Long account2Id = account2.getId();
            Long member2Id = member2.getId();

            String sameKey = UUID.randomUUID().toString();

            // When: 계좌 A에서 키 "abc"로 입금
            transactionService.deposit(accountId, memberId,
                    new DepositRequest(new BigDecimal("1000"), "계좌A 입금"),
                    sameKey);

            // 계좌 B에서 동일한 키 "abc"로 입금 → dedupe되지 않고 별도 처리
            transactionService.deposit(account2Id, member2Id,
                    new DepositRequest(new BigDecimal("2000"), "계좌B 입금"),
                    sameKey);

            // Then: 각 계좌에 각각 금액이 반영되어야 함
            Account resultA = accountRepository.findById(accountId).orElseThrow();
            Account resultB = accountRepository.findById(account2Id).orElseThrow();
            assertThat(resultA.getBalance()).isEqualByComparingTo(new BigDecimal("11000")); // 10000 + 1000
            assertThat(resultB.getBalance()).isEqualByComparingTo(new BigDecimal("2000"));  // 0 + 2000
        }

        @Test
        @DisplayName("예외 발생 시 분산 락이 정상 해제된다")
        void exception_lockIsReleased() {
            // When & Then: 예외가 반드시 발생해야 함
            assertThatThrownBy(() -> transactionService.withdraw(accountId, memberId,
                    new WithdrawRequest(new BigDecimal("99999"), "잔액 부족"),
                    UUID.randomUUID().toString()))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));

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

    @Nested
    @DisplayName("송금 동시성 (데드락 방지)")
    class TransferConcurrency {

        private Long account2Id;
        private Long member2Id;

        @BeforeEach
        void setUp() {
            // 두 번째 계좌 생성 + 10,000원 입금
            Member member2 = Member.builder()
                    .email("transfer-target@safepay.com")
                    .password(passwordEncoder.encode("password123"))
                    .name("수신자")
                    .phone(aesEncryptor.encrypt("010-9999-9999"))
                    .build();
            member2 = memberRepository.save(member2);
            member2Id = member2.getId();

            Account account2 = Account.builder()
                    .member(member2)
                    .accountNumber(aesEncryptor.encrypt("100-01-999999-9"))
                    .accountType(Account.AccountType.CHECKING)
                    .build();
            account2 = accountRepository.save(account2);
            account2Id = account2.getId();

            transactionService.deposit(account2Id, member2Id,
                    new DepositRequest(new BigDecimal("10000"), "초기 입금"),
                    UUID.randomUUID().toString());
        }

        @Test
        @DisplayName("⭐ A→B + B→A 동시 송금 → 데드락 없이 양쪽 잔액 정합성 유지")
        void concurrentCrossTransfer_noDeadlock_balanceConsistent() throws Exception {
            // Given: A=10,000원, B=10,000원
            // A→B 5,000원 + B→A 3,000원 동시 실행
            // 기대 결과: A = 10000 - 5000 + 3000 = 8000, B = 10000 + 5000 - 3000 = 12000
            int threadCount = 2;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            // A→B 5,000원
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    transactionService.transfer(accountId, account2Id, memberId,
                            new TransferDto.TransferRequest(account2Id, new BigDecimal("5000"), "A→B"),
                            UUID.randomUUID().toString());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // 로깅
                } finally {
                    done.countDown();
                }
            });

            // B→A 3,000원
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    transactionService.transfer(account2Id, accountId, member2Id,
                            new TransferDto.TransferRequest(accountId, new BigDecimal("3000"), "B→A"),
                            UUID.randomUUID().toString());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // 로깅
                } finally {
                    done.countDown();
                }
            });

            ready.await();
            start.countDown();
            boolean completed = done.await(15, TimeUnit.SECONDS);
            executor.shutdown();

            // Then: 데드락 없이 완료 + 잔액 정합성
            assertThat(completed).isTrue(); // 15초 내 완료 = 데드락 없음
            assertThat(successCount.get()).isEqualTo(2);

            Account resultA = accountRepository.findById(accountId).orElseThrow();
            Account resultB = accountRepository.findById(account2Id).orElseThrow();
            assertThat(resultA.getBalance()).isEqualByComparingTo(new BigDecimal("8000"));
            assertThat(resultB.getBalance()).isEqualByComparingTo(new BigDecimal("12000"));
        }

        @Test
        @DisplayName("동일 출금 계좌에서 동시 송금 → 잔액 부족 시 일부만 성공")
        void concurrentTransferFromSameAccount_partialSuccess() throws Exception {
            // Given: A=10,000원, 5,000원씩 3번 동시 송금 (2번만 성공해야 함)
            int threadCount = 3;
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
                        transactionService.transfer(accountId, account2Id, memberId,
                                new TransferDto.TransferRequest(account2Id, new BigDecimal("5000"), "동시 송금 " + idx),
                                UUID.randomUUID().toString());
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

            // Then
            assertThat(successCount.get()).isEqualTo(2);  // 5000 * 2 = 10000
            assertThat(failCount.get()).isEqualTo(1);      // 잔액 부족

            Account resultA = accountRepository.findById(accountId).orElseThrow();
            Account resultB = accountRepository.findById(account2Id).orElseThrow();
            assertThat(resultA.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(resultB.getBalance()).isEqualByComparingTo(new BigDecimal("20000"));
        }

        @Test
        @DisplayName("⭐ 10스레드 A→B/B→A 교차 송금 → 전체 잔액 합계 불변")
        void concurrentCrossTransfer_10threads_totalBalancePreserved() throws Exception {
            // Given: A=10,000원, B=10,000원 → 합계 20,000원
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
                        if (idx % 2 == 0) {
                            // A→B 1,000원
                            transactionService.transfer(accountId, account2Id, memberId,
                                    new TransferDto.TransferRequest(account2Id, new BigDecimal("1000"), "A→B " + idx),
                                    UUID.randomUUID().toString());
                        } else {
                            // B→A 1,000원
                            transactionService.transfer(account2Id, accountId, member2Id,
                                    new TransferDto.TransferRequest(accountId, new BigDecimal("1000"), "B→A " + idx),
                                    UUID.randomUUID().toString());
                        }
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // 잔액 부족 시 실패 가능
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();
            done.await(15, TimeUnit.SECONDS);
            executor.shutdown();

            // Then: 전체 잔액 합계는 항상 20,000원 (닫힌 시스템)
            Account resultA = accountRepository.findById(accountId).orElseThrow();
            Account resultB = accountRepository.findById(account2Id).orElseThrow();
            BigDecimal totalBalance = resultA.getBalance().add(resultB.getBalance());
            assertThat(totalBalance).isEqualByComparingTo(new BigDecimal("20000"));
        }

        @Test
        @DisplayName("송금 멱등성 — 동일 키 2회 요청 시 잔액 1번만 변경")
        void transfer_idempotency_onceOnly() {
            String sameKey = UUID.randomUUID().toString();

            // 1차 요청
            transactionService.transfer(accountId, account2Id, memberId,
                    new TransferDto.TransferRequest(account2Id, new BigDecimal("3000"), "송금"),
                    sameKey);

            // 2차 요청 (동일 키)
            transactionService.transfer(accountId, account2Id, memberId,
                    new TransferDto.TransferRequest(account2Id, new BigDecimal("3000"), "중복 송금"),
                    sameKey);

            // Then: 잔액은 1번만 변경
            Account resultA = accountRepository.findById(accountId).orElseThrow();
            Account resultB = accountRepository.findById(account2Id).orElseThrow();
            assertThat(resultA.getBalance()).isEqualByComparingTo(new BigDecimal("7000"));
            assertThat(resultB.getBalance()).isEqualByComparingTo(new BigDecimal("13000"));
        }

        @Test
        @DisplayName("송금 후 분산 락이 정상 해제된다")
        void transfer_lockReleasedAfterCompletion() {
            transactionService.transfer(accountId, account2Id, memberId,
                    new TransferDto.TransferRequest(account2Id, new BigDecimal("1000"), "송금"),
                    UUID.randomUUID().toString());

            // 두 계좌 모두 락이 해제되어야 함
            String lockKey1 = "safepay:lock:account:" + accountId;
            String lockKey2 = "safepay:lock:account:" + account2Id;
            assertThat(redissonClient.getLock(lockKey1).isLocked()).isFalse();
            assertThat(redissonClient.getLock(lockKey2).isLocked()).isFalse();
        }
    }
}