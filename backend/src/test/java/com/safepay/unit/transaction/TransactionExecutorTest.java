package com.safepay.unit.transaction;

import com.safepay.domain.account.entity.Account;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.member.entity.Member;
import com.safepay.domain.transaction.dto.TransactionDto.*;
import com.safepay.domain.transaction.entity.Transaction;
import com.safepay.domain.transaction.repository.TransactionRepository;
import com.safepay.domain.transaction.service.TransactionExecutor;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionExecutor 단위 테스트")
class TransactionExecutorTest {

    @InjectMocks
    private TransactionExecutor transactionExecutor;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

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

    private void depositToAccount(BigDecimal amount) {
        account.deposit(amount);
    }

    // 입금
    @Nested
    @DisplayName("입금")
    class Deposit {

        @Test
        @DisplayName("정상 입금 시 거래 내역을 반환한다")
        void deposit_success() {
            // Given
            DepositRequest request = new DepositRequest(new BigDecimal("10000"), "테스트 입금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(transactionRepository.save(any(Transaction.class))).willAnswer(invocation -> {
                Transaction tx = invocation.getArgument(0);
                ReflectionTestUtils.setField(tx, "id", 1L);
                return tx;
            });

            // When
            TransactionResponse response = transactionExecutor.executeDeposit(ACCOUNT_ID, MEMBER_ID, request, "test-key");

            // Then
            assertThat(response.getType()).isEqualTo("DEPOSIT");
            assertThat(response.getAmount()).isEqualByComparingTo(new BigDecimal("10000"));
            assertThat(response.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("10000"));
            assertThat(response.getStatus()).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("입금 후 계좌 잔액이 증가한다")
        void deposit_balanceIncreases() {
            // Given
            DepositRequest request = new DepositRequest(new BigDecimal("5000"), "입금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(transactionRepository.save(any(Transaction.class))).willAnswer(invocation -> {
                Transaction tx = invocation.getArgument(0);
                ReflectionTestUtils.setField(tx, "id", 1L);
                return tx;
            });

            // When
            transactionExecutor.executeDeposit(ACCOUNT_ID, MEMBER_ID, request, "test-key");

            // Then
            assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("5000"));
        }

        @Test
        @DisplayName("비관적 락으로 계좌를 조회한다 (findByIdWithLock)")
        void deposit_usesPessimisticLock() {
            // Given
            DepositRequest request = new DepositRequest(new BigDecimal("1000"), "입금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(transactionRepository.save(any(Transaction.class))).willAnswer(invocation -> {
                Transaction tx = invocation.getArgument(0);
                ReflectionTestUtils.setField(tx, "id", 1L);
                return tx;
            });

            // When
            transactionExecutor.executeDeposit(ACCOUNT_ID, MEMBER_ID, request, "test-key");

            // Then: findByIdWithLock이 호출됐는지 (findById가 아님)
            verify(accountRepository).findByIdWithLock(ACCOUNT_ID);
            verify(accountRepository, never()).findById(ACCOUNT_ID);
        }

        @Test
        @DisplayName("거래 내역이 DB에 저장된다")
        void deposit_transactionIsSaved() {
            // Given
            DepositRequest request = new DepositRequest(new BigDecimal("10000"), "입금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(transactionRepository.save(any(Transaction.class))).willAnswer(invocation -> {
                Transaction tx = invocation.getArgument(0);
                ReflectionTestUtils.setField(tx, "id", 1L);
                return tx;
            });

            // When
            transactionExecutor.executeDeposit(ACCOUNT_ID, MEMBER_ID, request, "test-key");

            // Then
            verify(transactionRepository).save(any(Transaction.class));
        }

        @Test
        @DisplayName("존재하지 않는 계좌에 입금하면 ACCOUNT_NOT_FOUND 예외가 발생한다")
        void deposit_accountNotFound_throwsException() {
            // Given
            DepositRequest request = new DepositRequest(new BigDecimal("10000"), "입금");

            given(accountRepository.findByIdWithLock(999L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> transactionExecutor.executeDeposit(999L, MEMBER_ID, request, "test-key"))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
        }

        @Test
        @DisplayName("본인 계좌가 아니면 ACCOUNT_NOT_OWNER 예외가 발생한다")
        void deposit_notOwner_throwsException() {
            // Given
            DepositRequest request = new DepositRequest(new BigDecimal("10000"), "입금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));

            // When & Then: memberId=999 (다른 사람)
            assertThatThrownBy(() -> transactionExecutor.executeDeposit(ACCOUNT_ID, 999L, request, "test-key"))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.ACCOUNT_NOT_OWNER));
        }
    }

    // 출금
    @Nested
    @DisplayName("출금")
    class Withdraw {

        @BeforeEach
        void setUp() {
            depositToAccount(new BigDecimal("10000"));
        }

        @Test
        @DisplayName("정상 출금 시 거래 내역을 반환한다")
        void withdraw_success() {
            // Given
            WithdrawRequest request = new WithdrawRequest(new BigDecimal("3000"), "테스트 출금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(transactionRepository.save(any(Transaction.class))).willAnswer(invocation -> {
                Transaction tx = invocation.getArgument(0);
                ReflectionTestUtils.setField(tx, "id", 1L);
                return tx;
            });

            // When
            TransactionResponse response = transactionExecutor.executeWithdraw(ACCOUNT_ID, MEMBER_ID, request, "test-key");

            // Then
            assertThat(response.getType()).isEqualTo("WITHDRAW");
            assertThat(response.getAmount()).isEqualByComparingTo(new BigDecimal("3000"));
            assertThat(response.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("7000"));
            assertThat(response.getStatus()).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("출금 후 계좌 잔액이 감소한다")
        void withdraw_balanceDecreases() {
            // Given
            WithdrawRequest request = new WithdrawRequest(new BigDecimal("3000"), "출금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(transactionRepository.save(any(Transaction.class))).willAnswer(invocation -> {
                Transaction tx = invocation.getArgument(0);
                ReflectionTestUtils.setField(tx, "id", 1L);
                return tx;
            });

            // When
            transactionExecutor.executeWithdraw(ACCOUNT_ID, MEMBER_ID, request, "test-key");

            // Then
            assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("7000"));
        }

        @Test
        @DisplayName("잔액보다 많은 금액 출금 시 INSUFFICIENT_BALANCE 예외가 발생한다")
        void withdraw_insufficientBalance_throwsException() {
            // Given
            WithdrawRequest request = new WithdrawRequest(new BigDecimal("10001"), "초과 출금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));

            // When & Then
            assertThatThrownBy(() -> transactionExecutor.executeWithdraw(ACCOUNT_ID, MEMBER_ID, request, "test-key"))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));
        }

        @Test
        @DisplayName("잔액 부족 시 거래 내역이 저장되지 않는다")
        void withdraw_insufficientBalance_noTransactionSaved() {
            // Given
            WithdrawRequest request = new WithdrawRequest(new BigDecimal("10001"), "초과 출금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));

            // When
            try {
                transactionExecutor.executeWithdraw(ACCOUNT_ID, MEMBER_ID, request, "test-key");
            } catch (CustomException ignored) {
            }

            // Then
            verify(transactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("잔액 부족 시 계좌 잔액은 변경되지 않는다")
        void withdraw_insufficientBalance_balanceUnchanged() {
            // Given
            WithdrawRequest request = new WithdrawRequest(new BigDecimal("10001"), "초과 출금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));

            // When
            try {
                transactionExecutor.executeWithdraw(ACCOUNT_ID, MEMBER_ID, request, "test-key");
            } catch (CustomException ignored) {
            }

            // Then
            assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("10000"));
        }

        @Test
        @DisplayName("잔액 전부 출금하면 잔액이 0원이 된다")
        void withdraw_allBalance_becomesZero() {
            // Given
            WithdrawRequest request = new WithdrawRequest(new BigDecimal("10000"), "전액 출금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(transactionRepository.save(any(Transaction.class))).willAnswer(invocation -> {
                Transaction tx = invocation.getArgument(0);
                ReflectionTestUtils.setField(tx, "id", 1L);
                return tx;
            });

            // When
            transactionExecutor.executeWithdraw(ACCOUNT_ID, MEMBER_ID, request, "test-key");

            // Then
            assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("본인 계좌가 아니면 ACCOUNT_NOT_OWNER 예외가 발생한다")
        void withdraw_notOwner_throwsException() {
            // Given
            WithdrawRequest request = new WithdrawRequest(new BigDecimal("1000"), "출금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));

            // When & Then: memberId=999 (다른 사람)
            assertThatThrownBy(() -> transactionExecutor.executeWithdraw(ACCOUNT_ID, 999L, request, "test-key"))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.ACCOUNT_NOT_OWNER));
        }
    }
}
