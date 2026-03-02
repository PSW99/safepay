package com.safepay.unit.account;

import com.safepay.domain.account.dto.AccountDto.*;
import com.safepay.domain.account.entity.Account;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.account.service.AccountService;
import com.safepay.domain.member.entity.Member;
import com.safepay.domain.member.repository.MemberRepository;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import com.safepay.global.util.AccountNumberGenerator;
import com.safepay.global.util.AesEncryptor;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountService 단위 테스트")
class AccountServiceTest {

    @InjectMocks
    private AccountService accountService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private AccountNumberGenerator accountNumberGenerator;

    @Mock
    private AesEncryptor aesEncryptor;

    private Member member;
    private Account account;

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
                .accountNumber("encryptedAccountNumber")
                .accountType(Account.AccountType.CHECKING)
                .build();
        ReflectionTestUtils.setField(account, "id", 1L);
    }

    // 계좌 개설
    @Nested
    @DisplayName("계좌 개설")
    class CreateAccount {

        @Test
        @DisplayName("정상 계좌 개설 시 accountId와 계좌번호를 반환한다")
        void createAccount_success() {
            // Given
            CreateRequest request = new CreateRequest(Account.AccountType.CHECKING);

            given(memberRepository.findById(1L)).willReturn(Optional.of(member));
            given(accountNumberGenerator.generate("CHECKING")).willReturn("100-01-123456-7");
            given(aesEncryptor.encrypt("100-01-123456-7")).willReturn("encryptedNumber");
            given(accountRepository.existsByAccountNumber("encryptedNumber")).willReturn(false);
            given(accountRepository.save(any(Account.class))).willReturn(account);

            // When
            CreateResponse response = accountService.createAccount(1L, request);

            // Then
            assertThat(response.getAccountId()).isEqualTo(1L);
            assertThat(response.getAccountNumber()).isEqualTo("100-01-123456-7");
        }

        @Test
        @DisplayName("계좌번호가 AES 암호화되어 저장된다")
        void createAccount_accountNumberIsEncrypted() {
            // Given
            CreateRequest request = new CreateRequest(Account.AccountType.CHECKING);

            given(memberRepository.findById(1L)).willReturn(Optional.of(member));
            given(accountNumberGenerator.generate("CHECKING")).willReturn("100-01-123456-7");
            given(aesEncryptor.encrypt("100-01-123456-7")).willReturn("encryptedNumber");
            given(accountRepository.existsByAccountNumber("encryptedNumber")).willReturn(false);
            given(accountRepository.save(any(Account.class))).willReturn(account);

            // When
            accountService.createAccount(1L, request);

            // Then
            verify(aesEncryptor).encrypt("100-01-123456-7");
            verify(accountRepository).save(any(Account.class));
        }

        @Test
        @DisplayName("존재하지 않는 회원이면 예외가 발생한다")
        void createAccount_memberNotFound_throwsException() {
            // Given
            CreateRequest request = new CreateRequest(Account.AccountType.CHECKING);
            given(memberRepository.findById(999L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> accountService.createAccount(999L, request))
                    .isInstanceOf(CustomException.class);
        }

        @Test
        @DisplayName("SAVINGS 타입으로 계좌를 개설할 수 있다")
        void createAccount_savingsType_success() {
            // Given
            CreateRequest request = new CreateRequest(Account.AccountType.SAVINGS);

            Account savingsAccount = Account.builder()
                    .member(member)
                    .accountNumber("encryptedSavings")
                    .accountType(Account.AccountType.SAVINGS)
                    .build();
            ReflectionTestUtils.setField(savingsAccount, "id", 2L);

            given(memberRepository.findById(1L)).willReturn(Optional.of(member));
            given(accountNumberGenerator.generate("SAVINGS")).willReturn("100-02-654321-3");
            given(aesEncryptor.encrypt("100-02-654321-3")).willReturn("encryptedSavings");
            given(accountRepository.existsByAccountNumber("encryptedSavings")).willReturn(false);
            given(accountRepository.save(any(Account.class))).willReturn(savingsAccount);

            // When
            CreateResponse response = accountService.createAccount(1L, request);

            // Then
            assertThat(response.getAccountNumber()).startsWith("100-02-");
        }
    }

    // 내 계좌 목록 조회
    @Nested
    @DisplayName("내 계좌 목록 조회")
    class GetMyAccounts {

        @Test
        @DisplayName("내 계좌 목록을 반환한다")
        void getMyAccounts_success() {
            // Given
            Account account2 = Account.builder()
                    .member(member)
                    .accountNumber("encryptedNumber2")
                    .accountType(Account.AccountType.SAVINGS)
                    .build();
            ReflectionTestUtils.setField(account2, "id", 2L);

            given(accountRepository.findByMemberId(1L)).willReturn(List.of(account, account2));
            given(aesEncryptor.decrypt("encryptedAccountNumber")).willReturn("100-01-123456-7");
            given(aesEncryptor.decrypt("encryptedNumber2")).willReturn("100-02-654321-3");

            // When
            List<AccountResponse> responses = accountService.getMyAccounts(1L);

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getAccountNumber()).isEqualTo("100-01-123456-7");
            assertThat(responses.get(1).getAccountNumber()).isEqualTo("100-02-654321-3");
        }

        @Test
        @DisplayName("계좌가 없으면 빈 목록을 반환한다")
        void getMyAccounts_noAccounts_returnsEmpty() {
            // Given
            given(accountRepository.findByMemberId(1L)).willReturn(List.of());

            // When
            List<AccountResponse> responses = accountService.getMyAccounts(1L);

            // Then
            assertThat(responses).isEmpty();
        }

        @Test
        @DisplayName("계좌번호가 복호화되어 반환된다")
        void getMyAccounts_accountNumberDecrypted() {
            // Given
            given(accountRepository.findByMemberId(1L)).willReturn(List.of(account));
            given(aesEncryptor.decrypt("encryptedAccountNumber")).willReturn("100-01-123456-7");

            // When
            List<AccountResponse> responses = accountService.getMyAccounts(1L);

            // Then
            verify(aesEncryptor).decrypt("encryptedAccountNumber");
            assertThat(responses.get(0).getAccountNumber()).isEqualTo("100-01-123456-7");
        }
    }

    // 계좌 상세 조회
    @Nested
    @DisplayName("계좌 상세 조회")
    class GetAccount {

        @Test
        @DisplayName("본인 계좌 상세 정보를 반환한다")
        void getAccount_success() {
            // Given
            given(accountRepository.findById(1L)).willReturn(Optional.of(account));
            given(aesEncryptor.decrypt("encryptedAccountNumber")).willReturn("100-01-123456-7");

            // When
            AccountResponse response = accountService.getAccount(1L, 1L);

            // Then
            assertThat(response.getAccountId()).isEqualTo(1L);
            assertThat(response.getAccountNumber()).isEqualTo("100-01-123456-7");
            assertThat(response.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(response.getAccountType()).isEqualTo("CHECKING");
            assertThat(response.getStatus()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("존재하지 않는 계좌이면 ACCOUNT_NOT_FOUND 예외가 발생한다")
        void getAccount_notFound_throwsException() {
            // Given
            given(accountRepository.findById(999L)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> accountService.getAccount(999L, 1L))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND));
        }

        @Test
        @DisplayName("본인 계좌가 아니면 ACCOUNT_NOT_OWNER 예외가 발생한다")
        void getAccount_notOwner_throwsException() {
            // Given
            given(accountRepository.findById(1L)).willReturn(Optional.of(account));

            // When & Then: memberId=999로 조회 (다른 사람)
            assertThatThrownBy(() -> accountService.getAccount(1L, 999L))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.ACCOUNT_NOT_OWNER));
        }
    }
}