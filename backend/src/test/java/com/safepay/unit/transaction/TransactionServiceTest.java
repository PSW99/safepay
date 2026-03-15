package com.safepay.unit.transaction;

import com.safepay.domain.account.entity.Account;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.member.entity.Member;
import com.safepay.domain.transaction.dto.TransactionDto.*;
import com.safepay.domain.transaction.entity.Transaction;
import com.safepay.domain.transaction.repository.TransactionRepository;
import com.safepay.domain.transaction.service.TransactionExecutor;
import com.safepay.domain.transaction.service.TransactionService;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import com.safepay.global.util.DistributedLockManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionService 단위 테스트")
class TransactionServiceTest {

    @InjectMocks
    private TransactionService transactionService;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private DistributedLockManager distributedLockManager;

    @Mock
    private TransactionExecutor transactionExecutor;

    @Mock
    private RLock rLock;

    private Member member;
    private Account account;
    private static final Long MEMBER_ID = 1L;
    private static final Long ACCOUNT_ID = 1L;

    @BeforeEach
    void setUp() {
        member = Member.builder()
                .email("test@safepay.com")
                .password("encodedPassword")
                .name("테스트")
                .phone("encryptedPhone")
                .build();
        ReflectionTestUtils.setField(member, "id", MEMBER_ID);

        account = Account.builder()
                .member(member)
                .accountNumber("encryptedNumber")
                .accountType(Account.AccountType.CHECKING)
                .build();
        ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);
    }

    private Transaction createSavedTransaction(String key, Transaction.TransactionType type,
                                               BigDecimal amount, BigDecimal balanceAfter) {
        Transaction tx = Transaction.builder()
                .account(account)
                .type(type)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .description("기존 거래")
                .idempotencyKey(key)
                .status(Transaction.TransactionStatus.SUCCESS)
                .build();
        ReflectionTestUtils.setField(tx, "id", 100L);
        return tx;
    }

    // 입금 오케스트레이션
    @Nested
    @DisplayName("입금")
    class Deposit {

        @Test
        @DisplayName("분산 락 획득 후 TransactionExecutor에 위임한다")
        void deposit_acquiresLockAndDelegates() {
            // Given
            DepositRequest request = new DepositRequest(new BigDecimal("10000"), "입금");
            TransactionResponse expectedResponse = new TransactionResponse(
                    1L, "DEPOSIT", new BigDecimal("10000"), new BigDecimal("10000"),
                    "입금", "SUCCESS", null);

            given(transactionRepository.findByIdempotencyKey("test-key")).willReturn(Optional.empty());
            given(distributedLockManager.tryLockOrNull(ACCOUNT_ID)).willReturn(rLock);
            given(transactionExecutor.executeDeposit(ACCOUNT_ID, MEMBER_ID, request, "test-key"))
                    .willReturn(expectedResponse);

            // When
            TransactionResponse response = transactionService.deposit(ACCOUNT_ID, MEMBER_ID, request, "test-key");

            // Then
            assertThat(response.getType()).isEqualTo("DEPOSIT");
            verify(distributedLockManager).tryLockOrNull(ACCOUNT_ID);
            verify(transactionExecutor).executeDeposit(ACCOUNT_ID, MEMBER_ID, request, "test-key");
            verify(distributedLockManager).unlock(rLock);
        }

        @Test
        @DisplayName("예외 발생 시에도 분산 락이 해제된다")
        void deposit_exceptionReleasesLock() {
            // Given
            DepositRequest request = new DepositRequest(new BigDecimal("10000"), "입금");

            given(transactionRepository.findByIdempotencyKey("test-key")).willReturn(Optional.empty());
            given(distributedLockManager.tryLockOrNull(ACCOUNT_ID)).willReturn(rLock);
            given(transactionExecutor.executeDeposit(ACCOUNT_ID, MEMBER_ID, request, "test-key"))
                    .willThrow(new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

            // When & Then
            assertThatThrownBy(() -> transactionService.deposit(ACCOUNT_ID, MEMBER_ID, request, "test-key"))
                    .isInstanceOf(CustomException.class);
            verify(distributedLockManager).unlock(rLock);
        }
    }

    // 출금 오케스트레이션
    @Nested
    @DisplayName("출금")
    class Withdraw {

        @Test
        @DisplayName("분산 락 획득 후 TransactionExecutor에 위임한다")
        void withdraw_acquiresLockAndDelegates() {
            // Given
            WithdrawRequest request = new WithdrawRequest(new BigDecimal("3000"), "출금");
            TransactionResponse expectedResponse = new TransactionResponse(
                    1L, "WITHDRAW", new BigDecimal("3000"), new BigDecimal("7000"),
                    "출금", "SUCCESS", null);

            given(transactionRepository.findByIdempotencyKey("test-key")).willReturn(Optional.empty());
            given(distributedLockManager.tryLockOrNull(ACCOUNT_ID)).willReturn(rLock);
            given(transactionExecutor.executeWithdraw(ACCOUNT_ID, MEMBER_ID, request, "test-key"))
                    .willReturn(expectedResponse);

            // When
            TransactionResponse response = transactionService.withdraw(ACCOUNT_ID, MEMBER_ID, request, "test-key");

            // Then
            assertThat(response.getType()).isEqualTo("WITHDRAW");
            verify(distributedLockManager).tryLockOrNull(ACCOUNT_ID);
            verify(transactionExecutor).executeWithdraw(ACCOUNT_ID, MEMBER_ID, request, "test-key");
            verify(distributedLockManager).unlock(rLock);
        }
    }

    // 멱등성
    @Nested
    @DisplayName("멱등성 (Idempotency)")
    class Idempotency {

        @Test
        @DisplayName("동일한 Idempotency Key로 입금 요청 시 기존 결과를 반환한다")
        void deposit_duplicateKey_returnsExisting() {
            // Given
            String sameKey = "same-key-uuid";
            DepositRequest request = new DepositRequest(new BigDecimal("10000"), "입금");

            Transaction existingTx = createSavedTransaction(
                    sameKey, Transaction.TransactionType.DEPOSIT,
                    new BigDecimal("10000"), new BigDecimal("10000"));

            given(transactionRepository.findByIdempotencyKey(sameKey))
                    .willReturn(Optional.of(existingTx));

            // When
            TransactionResponse response = transactionService.deposit(ACCOUNT_ID, MEMBER_ID, request, sameKey);

            // Then: 기존 거래 결과를 반환, executor 호출 없음
            assertThat(response.getTransactionId()).isEqualTo(100L);
            verify(distributedLockManager, never()).tryLockOrNull(any());
            verify(transactionExecutor, never()).executeDeposit(any(), any(), any(), any());
        }

        @Test
        @DisplayName("동일한 Idempotency Key로 출금 요청 시 기존 결과를 반환한다")
        void withdraw_duplicateKey_returnsExisting() {
            // Given
            String sameKey = "same-key-uuid";
            WithdrawRequest request = new WithdrawRequest(new BigDecimal("5000"), "출금");

            Transaction existingTx = createSavedTransaction(
                    sameKey, Transaction.TransactionType.WITHDRAW,
                    new BigDecimal("5000"), new BigDecimal("5000"));

            given(transactionRepository.findByIdempotencyKey(sameKey))
                    .willReturn(Optional.of(existingTx));

            // When
            TransactionResponse response = transactionService.withdraw(ACCOUNT_ID, MEMBER_ID, request, sameKey);

            // Then
            assertThat(response.getTransactionId()).isEqualTo(100L);
            verify(distributedLockManager, never()).tryLockOrNull(any());
            verify(transactionExecutor, never()).executeWithdraw(any(), any(), any(), any());
        }

        @Test
        @DisplayName("중복 요청 시 에러가 아닌 이전 성공 결과를 반환한다 (UX)")
        void duplicate_returnsExistingResult_notError() {
            // Given
            String sameKey = "same-key-uuid";
            DepositRequest request = new DepositRequest(new BigDecimal("10000"), "입금");

            Transaction existingTx = createSavedTransaction(
                    sameKey, Transaction.TransactionType.DEPOSIT,
                    new BigDecimal("10000"), new BigDecimal("10000"));

            given(transactionRepository.findByIdempotencyKey(sameKey))
                    .willReturn(Optional.of(existingTx));

            // When & Then: 예외가 발생하지 않고 정상 응답을 반환
            TransactionResponse response = transactionService.deposit(ACCOUNT_ID, MEMBER_ID, request, sameKey);
            assertThat(response.getStatus()).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("서로 다른 Idempotency Key는 각각 독립적으로 처리된다")
        void differentKeys_processedIndependently() {
            // Given
            String key1 = "key-1";
            String key2 = "key-2";
            DepositRequest request = new DepositRequest(new BigDecimal("5000"), "입금");

            given(transactionRepository.findByIdempotencyKey(key1)).willReturn(Optional.empty());
            given(transactionRepository.findByIdempotencyKey(key2)).willReturn(Optional.empty());
            given(distributedLockManager.tryLockOrNull(ACCOUNT_ID)).willReturn(rLock);
            given(transactionExecutor.executeDeposit(eq(ACCOUNT_ID), eq(MEMBER_ID), eq(request), any()))
                    .willReturn(new TransactionResponse(
                            1L, "DEPOSIT", new BigDecimal("5000"), new BigDecimal("5000"),
                            "입금", "SUCCESS", null));

            // When
            transactionService.deposit(ACCOUNT_ID, MEMBER_ID, request, key1);
            transactionService.deposit(ACCOUNT_ID, MEMBER_ID, request, key2);

            // Then: executor가 2번 호출됨
            verify(transactionExecutor).executeDeposit(ACCOUNT_ID, MEMBER_ID, request, key1);
            verify(transactionExecutor).executeDeposit(ACCOUNT_ID, MEMBER_ID, request, key2);
        }
    }

    // 거래 내역 조회
    @Nested
    @DisplayName("거래 내역 조회")
    class GetTransactions {

        @Test
        @DisplayName("계좌의 거래 내역을 페이징으로 반환한다")
        void getTransactions_success() {
            // Given
            Pageable pageable = PageRequest.of(0, 20);
            Transaction tx = createSavedTransaction(
                    "key-1", Transaction.TransactionType.DEPOSIT,
                    new BigDecimal("10000"), new BigDecimal("10000"));

            given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(transactionRepository.findByAccountIdOrderByCreatedAtDesc(eq(ACCOUNT_ID), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(tx)));

            // When
            Page<TransactionResponse> result = transactionService.getTransactions(ACCOUNT_ID, MEMBER_ID, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getType()).isEqualTo("DEPOSIT");
        }

        @Test
        @DisplayName("거래 내역이 없으면 빈 페이지를 반환한다")
        void getTransactions_empty() {
            // Given
            Pageable pageable = PageRequest.of(0, 20);
            given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(transactionRepository.findByAccountIdOrderByCreatedAtDesc(eq(ACCOUNT_ID), any(Pageable.class)))
                    .willReturn(Page.empty());

            // When
            Page<TransactionResponse> result = transactionService.getTransactions(ACCOUNT_ID, MEMBER_ID, pageable);

            // Then
            assertThat(result.getContent()).isEmpty();
        }

        @Test
        @DisplayName("존재하지 않는 계좌의 거래 내역 조회 시 ACCOUNT_NOT_FOUND 예외")
        void getTransactions_accountNotFound_throwsException() {
            // Given
            Pageable pageable = PageRequest.of(0, 20);
            given(accountRepository.findById(999L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> transactionService.getTransactions(999L, MEMBER_ID, pageable))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
        }

        @Test
        @DisplayName("본인 계좌가 아니면 ACCOUNT_NOT_OWNER 예외")
        void getTransactions_notOwner_throwsException() {
            // Given
            Pageable pageable = PageRequest.of(0, 20);
            given(accountRepository.findById(ACCOUNT_ID)).willReturn(Optional.of(account));

            // When & Then
            assertThatThrownBy(() -> transactionService.getTransactions(ACCOUNT_ID, 999L, pageable))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.ACCOUNT_NOT_OWNER));
        }
    }
}
