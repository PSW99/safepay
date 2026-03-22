package com.safepay.unit.transaction;

import com.safepay.domain.account.entity.Account;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.member.entity.Member;
import com.safepay.domain.transaction.dto.TransactionDto.*;
import com.safepay.domain.transaction.entity.Transaction;
import com.safepay.domain.transaction.repository.TransactionRepository;
import com.safepay.domain.transaction.service.TransactionExecutor;
import com.safepay.domain.transaction.dto.TransferDto;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.mockito.Mockito.*;

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
        @DisplayName("중복 idempotencyKey로 저장 시 DUPLICATE_TRANSACTION 예외가 발생한다")
        void deposit_duplicateKey_throwsDuplicateTransaction() {
            // Given
            DepositRequest request = new DepositRequest(new BigDecimal("10000"), "입금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(transactionRepository.save(any(Transaction.class)))
                    .willThrow(new DataIntegrityViolationException("Duplicate entry for idempotency_key"));

            // When & Then
            assertThatThrownBy(() -> transactionExecutor.executeDeposit(ACCOUNT_ID, MEMBER_ID, request, "duplicate-key"))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.DUPLICATE_TRANSACTION));
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
        @DisplayName("중복 idempotencyKey로 저장 시 DUPLICATE_TRANSACTION 예외가 발생한다")
        void withdraw_duplicateKey_throwsDuplicateTransaction() {
            // Given
            WithdrawRequest request = new WithdrawRequest(new BigDecimal("3000"), "출금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(transactionRepository.save(any(Transaction.class)))
                    .willThrow(new DataIntegrityViolationException("Duplicate entry for idempotency_key"));

            // When & Then
            assertThatThrownBy(() -> transactionExecutor.executeWithdraw(ACCOUNT_ID, MEMBER_ID, request, "duplicate-key"))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.DUPLICATE_TRANSACTION));
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

    // 송금
    @Nested
    @DisplayName("송금")
    class Transfer {

        private Account toAccount;
        private static final Long TO_ACCOUNT_ID = 2L;

        @BeforeEach
        void setUp() {
            depositToAccount(new BigDecimal("10000")); // 출금 계좌 잔액

            Member toMember = Member.builder()
                    .email("to@safepay.com")
                    .password("encodedPassword")
                    .name("수신자")
                    .phone("encryptedPhone")
                    .build();
            ReflectionTestUtils.setField(toMember, "id", 2L);

            toAccount = Account.builder()
                    .member(toMember)
                    .accountNumber("encryptedNumber2")
                    .accountType(Account.AccountType.CHECKING)
                    .build();
            ReflectionTestUtils.setField(toAccount, "id", TO_ACCOUNT_ID);
            toAccount.deposit(new BigDecimal("5000")); // 수신 계좌 잔액
        }

        @Test
        @DisplayName("정상 송금 시 출금 계좌 잔액 감소, 입금 계좌 잔액 증가")
        void transfer_success_balancesUpdated() {
            // Given
            TransferDto.TransferRequest request = new TransferDto.TransferRequest(TO_ACCOUNT_ID, new BigDecimal("3000"), "송금");

            // ID 오름차순 락: 1 → 2
            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(accountRepository.findByIdWithLock(TO_ACCOUNT_ID)).willReturn(Optional.of(toAccount));
            given(transactionRepository.save(any(Transaction.class))).willAnswer(invocation -> {
                Transaction tx = invocation.getArgument(0);
                ReflectionTestUtils.setField(tx, "id", (long)(Math.random() * 1000));
                return tx;
            });

            // When
            TransferDto.TransferResponse response = transactionExecutor.executeTransfer(
                    ACCOUNT_ID, TO_ACCOUNT_ID, MEMBER_ID, request, "transfer-key");

            // Then
            assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("7000"));    // 10000 - 3000
            assertThat(toAccount.getBalance()).isEqualByComparingTo(new BigDecimal("8000"));   // 5000 + 3000
            assertThat(response.getAmount()).isEqualByComparingTo(new BigDecimal("3000"));
            assertThat(response.getFromBalanceAfter()).isEqualByComparingTo(new BigDecimal("7000"));
            assertThat(response.getToBalanceAfter()).isEqualByComparingTo(new BigDecimal("8000"));
            assertThat(response.getStatus()).isEqualTo("SUCCESS");
        }

        @Test
        @DisplayName("거래 내역이 2건 (TRANSFER_OUT + TRANSFER_IN) 저장된다")
        void transfer_savesTwoTransactions() {
            // Given
            TransferDto.TransferRequest request = new TransferDto.TransferRequest(TO_ACCOUNT_ID, new BigDecimal("3000"), "송금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(accountRepository.findByIdWithLock(TO_ACCOUNT_ID)).willReturn(Optional.of(toAccount));
            given(transactionRepository.save(any(Transaction.class))).willAnswer(invocation -> {
                Transaction tx = invocation.getArgument(0);
                ReflectionTestUtils.setField(tx, "id", (long)(Math.random() * 1000));
                return tx;
            });

            // When
            transactionExecutor.executeTransfer(ACCOUNT_ID, TO_ACCOUNT_ID, MEMBER_ID, request, "transfer-key");

            // Then: save가 2번 호출
            verify(transactionRepository, times(2)).save(any(Transaction.class));
        }

        @Test
        @DisplayName("ID 오름차순으로 비관적 락을 획득한다 (데드락 방지)")
        void transfer_locksInAscendingOrder() {
            // Given: fromId=2, toId=1 → 락 순서는 1 → 2
            Long fromId = 2L;
            Long toId = 1L;
            TransferDto.TransferRequest request = new TransferDto.TransferRequest(toId, new BigDecimal("3000"), "송금");

            // toAccount의 소유자를 fromId=2의 멤버로 설정
            Member fromMember = Member.builder()
                    .email("from2@safepay.com").password("pw").name("발신자").phone("phone").build();
            ReflectionTestUtils.setField(fromMember, "id", 3L);

            Account fromAcc = Account.builder()
                    .member(fromMember).accountNumber("enc3").accountType(Account.AccountType.CHECKING).build();
            ReflectionTestUtils.setField(fromAcc, "id", fromId);
            fromAcc.deposit(new BigDecimal("10000"));

            // min(2,1)=1 먼저, max(2,1)=2 다음
            given(accountRepository.findByIdWithLock(1L)).willReturn(Optional.of(account));  // toAccount
            given(accountRepository.findByIdWithLock(2L)).willReturn(Optional.of(fromAcc));  // fromAccount
            given(transactionRepository.save(any(Transaction.class))).willAnswer(invocation -> {
                Transaction tx = invocation.getArgument(0);
                ReflectionTestUtils.setField(tx, "id", 1L);
                return tx;
            });

            // When
            transactionExecutor.executeTransfer(fromId, toId, 3L, request, "key");

            // Then: ID 1을 먼저 조회 (오름차순)
            InOrder inOrder = inOrder(accountRepository);
            inOrder.verify(accountRepository).findByIdWithLock(1L);
            inOrder.verify(accountRepository).findByIdWithLock(2L);
        }

        @Test
        @DisplayName("자기 자신에게 송금하면 SELF_TRANSFER 예외가 발생한다")
        void transfer_selfTransfer_throwsException() {
            // Given
            TransferDto.TransferRequest request = new TransferDto.TransferRequest(ACCOUNT_ID, new BigDecimal("3000"), "자기송금");

            // When & Then
            assertThatThrownBy(() -> transactionExecutor.executeTransfer(
                    ACCOUNT_ID, ACCOUNT_ID, MEMBER_ID, request, "key"))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.SELF_TRANSFER));
        }

        @Test
        @DisplayName("잔액 부족 시 INSUFFICIENT_BALANCE 예외, 양쪽 잔액 변동 없음")
        void transfer_insufficientBalance_noChange() {
            // Given
            TransferDto.TransferRequest request = new TransferDto.TransferRequest(TO_ACCOUNT_ID, new BigDecimal("99999"), "초과 송금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(accountRepository.findByIdWithLock(TO_ACCOUNT_ID)).willReturn(Optional.of(toAccount));

            // When & Then
            assertThatThrownBy(() -> transactionExecutor.executeTransfer(
                    ACCOUNT_ID, TO_ACCOUNT_ID, MEMBER_ID, request, "key"))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));

            // 양쪽 잔액 변동 없음
            assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("10000"));
            assertThat(toAccount.getBalance()).isEqualByComparingTo(new BigDecimal("5000"));
        }

        @Test
        @DisplayName("본인 계좌가 아니면 ACCOUNT_NOT_OWNER 예외가 발생한다")
        void transfer_notOwner_throwsException() {
            // Given
            TransferDto.TransferRequest request = new TransferDto.TransferRequest(TO_ACCOUNT_ID, new BigDecimal("3000"), "송금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(accountRepository.findByIdWithLock(TO_ACCOUNT_ID)).willReturn(Optional.of(toAccount));

            // When & Then: memberId=999 (다른 사람)
            assertThatThrownBy(() -> transactionExecutor.executeTransfer(
                    ACCOUNT_ID, TO_ACCOUNT_ID, 999L, request, "key"))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.ACCOUNT_NOT_OWNER));
        }

        @Test
        @DisplayName("대상 계좌가 존재하지 않으면 TRANSFER_ACCOUNT_NOT_FOUND 예외가 발생한다")
        void transfer_toAccountNotFound_throwsException() {
            // Given
            TransferDto.TransferRequest request = new TransferDto.TransferRequest(999L, new BigDecimal("3000"), "송금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(accountRepository.findByIdWithLock(999L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> transactionExecutor.executeTransfer(
                    ACCOUNT_ID, 999L, MEMBER_ID, request, "key"))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.TRANSFER_ACCOUNT_NOT_FOUND));
        }

        @Test
        @DisplayName("중복 idempotencyKey로 저장 시 DUPLICATE_TRANSACTION 예외가 발생한다")
        void transfer_duplicateKey_throwsException() {
            // Given
            TransferDto.TransferRequest request = new TransferDto.TransferRequest(TO_ACCOUNT_ID, new BigDecimal("3000"), "송금");

            given(accountRepository.findByIdWithLock(ACCOUNT_ID)).willReturn(Optional.of(account));
            given(accountRepository.findByIdWithLock(TO_ACCOUNT_ID)).willReturn(Optional.of(toAccount));
            given(transactionRepository.save(any(Transaction.class)))
                    .willThrow(new DataIntegrityViolationException("Duplicate"));

            // When & Then
            assertThatThrownBy(() -> transactionExecutor.executeTransfer(
                    ACCOUNT_ID, TO_ACCOUNT_ID, MEMBER_ID, request, "dup-key"))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.DUPLICATE_TRANSACTION));
        }
    }
}
