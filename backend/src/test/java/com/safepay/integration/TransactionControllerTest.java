
package com.safepay.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safepay.domain.account.dto.AccountDto;
import com.safepay.domain.account.entity.Account;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.member.dto.AuthDto.*;
import com.safepay.domain.member.repository.MemberRepository;
import com.safepay.domain.transaction.dto.TransactionDto.*;
import com.safepay.domain.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@DisplayName("Transaction API 통합 테스트")
class TransactionControllerTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    private String accessToken;
    private Long accountId;

    @BeforeEach
    void setUp() throws Exception {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        memberRepository.deleteAll();

        accessToken = signupAndLogin();
        accountId = createCheckingAccount();
    }

    // 헬퍼 메서드

    private String signupAndLogin() throws Exception {
        SignupRequest signup = new SignupRequest(
                "test@safepay.com", "password123", "테스트유저", "010-1234-5678");
        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signup)));

        LoginRequest login = new LoginRequest("test@safepay.com", "password123");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andReturn();

        return objectMapper.readValue(
                result.getResponse().getContentAsString(), LoginResponse.class).getAccessToken();
    }

    private Long createCheckingAccount() throws Exception {
        AccountDto.CreateRequest request = new AccountDto.CreateRequest(Account.AccountType.CHECKING);
        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        return objectMapper.readValue(
                result.getResponse().getContentAsString(), AccountDto.CreateResponse.class).getAccountId();
    }

    private void deposit(BigDecimal amount) throws Exception {
        DepositRequest request = new DepositRequest(amount, "입금");
        mockMvc.perform(post("/api/v1/accounts/{id}/deposit", accountId)
                .header("Authorization", "Bearer " + accessToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    // POST /api/v1/accounts/{id}/deposit
    @Nested
    @DisplayName("POST /api/v1/accounts/{id}/deposit")
    class DepositApi {

        @Test
        @DisplayName("정상 입금 시 201 Created와 거래 내역을 반환한다")
        void deposit_success_returns201() throws Exception {
            DepositRequest request = new DepositRequest(new BigDecimal("10000"), "테스트 입금");

            mockMvc.perform(post("/api/v1/accounts/{id}/deposit", accountId)
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("DEPOSIT"))
                    .andExpect(jsonPath("$.amount").value(10000))
                    .andExpect(jsonPath("$.balanceAfter").value(10000))
                    .andExpect(jsonPath("$.status").value("SUCCESS"));
        }

        @Test
        @DisplayName("입금 후 잔액이 DB에 반영된다")
        void deposit_balancePersisted() throws Exception {
            deposit(new BigDecimal("10000"));

            Account account = accountRepository.findById(accountId).orElseThrow();
            assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("10000"));
        }

        @Test
        @DisplayName("동일 Idempotency Key로 2번 요청 시 잔액은 1번만 변경된다")
        void deposit_duplicateKey_idempotent() throws Exception {
            String sameKey = UUID.randomUUID().toString();
            DepositRequest request = new DepositRequest(new BigDecimal("10000"), "입금");
            String json = objectMapper.writeValueAsString(request);

            // 1차 요청
            mockMvc.perform(post("/api/v1/accounts/{id}/deposit", accountId)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Idempotency-Key", sameKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json));

            // 2차 요청 (동일 키)
            mockMvc.perform(post("/api/v1/accounts/{id}/deposit", accountId)
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Idempotency-Key", sameKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isCreated());

            // Then: 잔액은 10,000원 (1번만 반영)
            Account account = accountRepository.findById(accountId).orElseThrow();
            assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("10000"));
        }

        @Test
        @DisplayName("Idempotency-Key 헤더가 없으면 400을 반환한다")
        void deposit_noIdempotencyKey_returns400() throws Exception {
            DepositRequest request = new DepositRequest(new BigDecimal("10000"), "입금");

            mockMvc.perform(post("/api/v1/accounts/{id}/deposit", accountId)
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("인증 없이 입금하면 401을 반환한다")
        void deposit_withoutToken_returns401() throws Exception {
            DepositRequest request = new DepositRequest(new BigDecimal("10000"), "입금");

            mockMvc.perform(post("/api/v1/accounts/{id}/deposit", accountId)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("금액이 0 이하면 400을 반환한다")
        void deposit_invalidAmount_returns400() throws Exception {
            DepositRequest request = new DepositRequest(new BigDecimal("0"), "0원 입금");

            mockMvc.perform(post("/api/v1/accounts/{id}/deposit", accountId)
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }
    }

    // POST /api/v1/accounts/{id}/withdraw
    @Nested
    @DisplayName("POST /api/v1/accounts/{id}/withdraw")
    class WithdrawApi {

        @BeforeEach
        void setUp() throws Exception {
            // 잔액 10,000원 세팅
            deposit(new BigDecimal("10000"));
        }

        @Test
        @DisplayName("정상 출금 시 201 Created와 거래 내역을 반환한다")
        void withdraw_success_returns201() throws Exception {
            WithdrawRequest request = new WithdrawRequest(new BigDecimal("3000"), "테스트 출금");

            mockMvc.perform(post("/api/v1/accounts/{id}/withdraw", accountId)
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("WITHDRAW"))
                    .andExpect(jsonPath("$.amount").value(3000))
                    .andExpect(jsonPath("$.balanceAfter").value(7000));
        }

        @Test
        @DisplayName("잔액 부족 시 400을 반환한다")
        void withdraw_insufficientBalance_returns400() throws Exception {
            WithdrawRequest request = new WithdrawRequest(new BigDecimal("10001"), "초과 출금");

            mockMvc.perform(post("/api/v1/accounts/{id}/withdraw", accountId)
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("TX_001"));
        }

        @Test
        @DisplayName("잔액 부족 시 잔액은 변경되지 않는다")
        void withdraw_insufficientBalance_balanceUnchanged() throws Exception {
            WithdrawRequest request = new WithdrawRequest(new BigDecimal("10001"), "초과 출금");

            mockMvc.perform(post("/api/v1/accounts/{id}/withdraw", accountId)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));

            Account account = accountRepository.findById(accountId).orElseThrow();
            assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("10000"));
        }

        @Test
        @DisplayName("동일 Idempotency Key로 출금 2번 요청 시 잔액은 1번만 변경된다")
        void withdraw_duplicateKey_idempotent() throws Exception {
            String sameKey = UUID.randomUUID().toString();
            WithdrawRequest request = new WithdrawRequest(new BigDecimal("3000"), "출금");
            String json = objectMapper.writeValueAsString(request);

            mockMvc.perform(post("/api/v1/accounts/{id}/withdraw", accountId)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Idempotency-Key", sameKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json));

            mockMvc.perform(post("/api/v1/accounts/{id}/withdraw", accountId)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Idempotency-Key", sameKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json));

            Account account = accountRepository.findById(accountId).orElseThrow();
            assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("7000"));
        }
    }

    // GET /api/v1/accounts/{id}/transactions
    @Nested
    @DisplayName("GET /api/v1/accounts/{id}/transactions")
    class GetTransactionsApi {

        @Test
        @DisplayName("거래 내역을 페이징으로 반환한다")
        void getTransactions_success() throws Exception {
            // Given: 입금 2번
            deposit(new BigDecimal("5000"));
            deposit(new BigDecimal("3000"));

            // When & Then
            mockMvc.perform(get("/api/v1/accounts/{id}/transactions", accountId)
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.content[0].type").value("DEPOSIT"));
        }

        @Test
        @DisplayName("거래 내역이 없으면 빈 리스트를 반환한다")
        void getTransactions_empty() throws Exception {
            mockMvc.perform(get("/api/v1/accounts/{id}/transactions", accountId)
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(0));
        }

        @Test
        @DisplayName("존재하지 않는 계좌의 거래 내역 조회 시 404를 반환한다")
        void getTransactions_accountNotFound_returns404() throws Exception {
            mockMvc.perform(get("/api/v1/accounts/{id}/transactions", 9999)
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_001"));
        }

        @Test
        @DisplayName("타인의 계좌 거래 내역 조회 시 403을 반환한다")
        void getTransactions_notOwner_returns403() throws Exception {
            // 다른 사용자
            SignupRequest signup2 = new SignupRequest(
                    "other@safepay.com", "password123", "다른유저", "010-9999-8888");
            mockMvc.perform(post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(signup2)));

            LoginRequest login2 = new LoginRequest("other@safepay.com", "password123");
            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(login2)))
                    .andReturn();

            String otherToken = objectMapper.readValue(
                    result.getResponse().getContentAsString(), LoginResponse.class).getAccessToken();

            mockMvc.perform(get("/api/v1/accounts/{id}/transactions", accountId)
                            .header("Authorization", "Bearer " + otherToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_003"));
        }

        @Test
        @DisplayName("인증 없이 조회하면 401을 반환한다")
        void getTransactions_withoutToken_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/accounts/{id}/transactions", accountId))
                    .andExpect(status().isUnauthorized());
        }
    }
}