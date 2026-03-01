package com.safepay.unit.account;

import com.safepay.domain.account.entity.Account;
import com.safepay.domain.member.entity.Member;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Account 엔티티 도메인 로직 테스트")
class AccountTest {

    private Account account;
    private Member member;

    @BeforeEach
    void setUp() {
        member = Member.builder()
                .email("test@safepay.com")
                .password("encodedPassword")
                .name("테스트")
                .phone("encryptedPhone")
                .build();
        ReflectionTestUtils.setField(member, "id", 1L);

        account = Account.builder()
                .member(member)
                .accountNumber("encrypted-100-01-123456-7")
                .accountType(Account.AccountType.CHECKING)
                .build();
        ReflectionTestUtils.setField(account, "id", 1L);
    }

    // 입금 (deposit)
    @Nested
    @DisplayName("입금")
    class Deposit {

        @Test
        @DisplayName("정상 입금 시 잔액이 증가한다")
        void deposit_success() {
            // When
            account.deposit(new BigDecimal("10000"));

            // Then
            assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("10000"));
        }

        @Test
        @DisplayName("여러 번 입금하면 잔액이 누적된다")
        void deposit_multiple_accumulates() {
            // When
            account.deposit(new BigDecimal("5000"));
            account.deposit(new BigDecimal("3000"));

            // Then
            assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("8000"));
        }

        @Test
        @DisplayName("소수점 금액도 정확하게 처리한다 (부동소수점 오차 없음)")
        void deposit_decimalAmount_noPrecisionLoss() {
            // When
            account.deposit(new BigDecimal("0.10"));
            account.deposit(new BigDecimal("0.20"));

            // Then: 0.1 + 0.2 = 정확히 0.30 (float/double이면 0.30000000000000004)
            assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("0.30"));
        }

        @Test
        @DisplayName("0원 이하 입금은 INVALID_AMOUNT 예외가 발생한다")
        void deposit_zeroAmount_throwsException() {
            assertThatThrownBy(() -> account.deposit(BigDecimal.ZERO))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_AMOUNT));
        }

        @Test
        @DisplayName("음수 금액 입금은 INVALID_AMOUNT 예외가 발생한다")
        void deposit_negativeAmount_throwsException() {
            assertThatThrownBy(() -> account.deposit(new BigDecimal("-1000")))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_AMOUNT));
        }

        @Test
        @DisplayName("null 금액 입금은 INVALID_AMOUNT 예외가 발생한다")
        void deposit_nullAmount_throwsException() {
            assertThatThrownBy(() -> account.deposit(null))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_AMOUNT));
        }

        @Test
        @DisplayName("비활성 계좌에 입금하면 ACCOUNT_NOT_ACTIVE 예외가 발생한다")
        void deposit_frozenAccount_throwsException() {
            // Given
            ReflectionTestUtils.setField(account, "status", Account.AccountStatus.FROZEN);

            // When & Then
            assertThatThrownBy(() -> account.deposit(new BigDecimal("10000")))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE));
        }
    }

    // 출금 (withdraw)
    @Nested
    @DisplayName("출금")
    class Withdraw {

        @BeforeEach
        void setUp() {
            // 잔액 10,000원 세팅
            account.deposit(new BigDecimal("10000"));
        }

        @Test
        @DisplayName("정상 출금 시 잔액이 감소한다")
        void withdraw_success() {
            // When
            account.withdraw(new BigDecimal("3000"));

            // Then
            assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("7000"));
        }

        @Test
        @DisplayName("잔액 전부를 출금하면 잔액이 0원이 된다")
        void withdraw_allBalance_becomesZero() {
            // When
            account.withdraw(new BigDecimal("10000"));

            // Then
            assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("잔액보다 많은 금액을 출금하면 INSUFFICIENT_BALANCE 예외가 발생한다")
        void withdraw_insufficientBalance_throwsException() {
            assertThatThrownBy(() -> account.withdraw(new BigDecimal("10001")))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));
        }

        @Test
        @DisplayName("잔액 부족 시 잔액은 변경되지 않는다")
        void withdraw_insufficientBalance_balanceUnchanged() {
            // When
            try {
                account.withdraw(new BigDecimal("10001"));
            } catch (CustomException ignored) {
            }

            // Then: 잔액 그대로 10,000원
            assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("10000"));
        }

        @Test
        @DisplayName("0원 이하 출금은 INVALID_AMOUNT 예외가 발생한다")
        void withdraw_zeroAmount_throwsException() {
            assertThatThrownBy(() -> account.withdraw(BigDecimal.ZERO))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_AMOUNT));
        }

        @Test
        @DisplayName("비활성 계좌에서 출금하면 ACCOUNT_NOT_ACTIVE 예외가 발생한다")
        void withdraw_closedAccount_throwsException() {
            // Given
            ReflectionTestUtils.setField(account, "status", Account.AccountStatus.CLOSED);

            // When & Then
            assertThatThrownBy(() -> account.withdraw(new BigDecimal("1000")))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.ACCOUNT_NOT_ACTIVE));
        }
    }

    // 소유자 확인 (isOwnedBy)
    @Nested
    @DisplayName("소유자 확인")
    class IsOwnedBy {

        @Test
        @DisplayName("본인 계좌이면 true를 반환한다")
        void isOwnedBy_owner_returnsTrue() {
            assertThat(account.isOwnedBy(1L)).isTrue();
        }

        @Test
        @DisplayName("타인 계좌이면 false를 반환한다")
        void isOwnedBy_notOwner_returnsFalse() {
            assertThat(account.isOwnedBy(999L)).isFalse();
        }
    }

    // 초기 상태 검증
    @Nested
    @DisplayName("계좌 생성 시 초기 상태")
    class InitialState {

        @Test
        @DisplayName("생성 시 잔액은 0원이다")
        void newAccount_balanceIsZero() {
            assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("생성 시 상태는 ACTIVE이다")
        void newAccount_statusIsActive() {
            assertThat(account.getStatus()).isEqualTo(Account.AccountStatus.ACTIVE);
        }
    }
}