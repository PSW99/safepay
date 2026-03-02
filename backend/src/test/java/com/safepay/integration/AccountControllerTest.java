package com.safepay.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safepay.domain.account.dto.AccountDto.*;
import com.safepay.domain.account.entity.Account;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.member.dto.AuthDto.*;
import com.safepay.domain.member.repository.MemberRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@DisplayName("Account API 통합 테스트")
class AccountControllerTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private AccountRepository accountRepository;

    private String accessToken;

    @BeforeEach
    void setUp() throws Exception {
        accountRepository.deleteAll();
        memberRepository.deleteAll();

        // 회원가입 + 로그인하여 토큰 획득
        accessToken = signupAndLogin();
    }

    // 헬퍼 메서드

    private String signupAndLogin() throws Exception {
        // 회원가입
        SignupRequest signupRequest = new SignupRequest(
                "test@safepay.com", "password123", "테스트유저", "010-1234-5678");

        mockMvc.perform(post("/api/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(signupRequest)));

        // 로그인
        LoginRequest loginRequest = new LoginRequest("test@safepay.com", "password123");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andReturn();

        LoginResponse loginResponse = objectMapper.readValue(
                result.getResponse().getContentAsString(), LoginResponse.class);
        return loginResponse.getAccessToken();
    }

    private Long createCheckingAccount() throws Exception {
        CreateRequest request = new CreateRequest(Account.AccountType.CHECKING);

        MvcResult result = mockMvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        CreateResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), CreateResponse.class);
        return response.getAccountId();
    }

    // POST /api/v1/accounts — 계좌 개설
    @Nested
    @DisplayName("POST /api/v1/accounts")
    class CreateAccountApi {

        @Test
        @DisplayName("정상 계좌 개설 시 201 Created를 반환한다")
        void createAccount_success_returns201() throws Exception {
            // Given
            CreateRequest request = new CreateRequest(Account.AccountType.CHECKING);

            // When & Then
            mockMvc.perform(post("/api/v1/accounts")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accountId").isNumber())
                    .andExpect(jsonPath("$.accountNumber").isNotEmpty());
        }

        @Test
        @DisplayName("계좌 개설 후 DB에 저장된다")
        void createAccount_isPersisted() throws Exception {
            // Given
            CreateRequest request = new CreateRequest(Account.AccountType.CHECKING);

            // When
            mockMvc.perform(post("/api/v1/accounts")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));

            // Then
            List<Account> accounts = accountRepository.findAll();
            assertThat(accounts).hasSize(1);
            assertThat(accounts.get(0).getAccountType()).isEqualTo(Account.AccountType.CHECKING);
        }

        @Test
        @DisplayName("계좌번호가 암호화되어 저장된다")
        void createAccount_accountNumberIsEncrypted() throws Exception {
            // Given
            CreateRequest request = new CreateRequest(Account.AccountType.CHECKING);

            // When
            mockMvc.perform(post("/api/v1/accounts")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));

            // Then: DB에 저장된 계좌번호는 평문이 아님
            Account saved = accountRepository.findAll().get(0);
            assertThat(saved.getAccountNumber()).doesNotMatch("\\d{3}-\\d{2}-\\d{6}-\\d");
        }

        @Test
        @DisplayName("SAVINGS 타입으로 계좌를 개설할 수 있다")
        void createAccount_savingsType() throws Exception {
            // Given
            CreateRequest request = new CreateRequest(Account.AccountType.SAVINGS);

            // When & Then
            mockMvc.perform(post("/api/v1/accounts")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());

            Account saved = accountRepository.findAll().get(0);
            assertThat(saved.getAccountType()).isEqualTo(Account.AccountType.SAVINGS);
        }

        @Test
        @DisplayName("초기 잔액은 0원이다")
        void createAccount_initialBalanceIsZero() throws Exception {
            // Given
            CreateRequest request = new CreateRequest(Account.AccountType.CHECKING);

            // When
            mockMvc.perform(post("/api/v1/accounts")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));

            // Then
            Account saved = accountRepository.findAll().get(0);
            assertThat(saved.getBalance()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("인증 없이 계좌 개설하면 401을 반환한다")
        void createAccount_withoutToken_returns401() throws Exception {
            CreateRequest request = new CreateRequest(Account.AccountType.CHECKING);

            mockMvc.perform(post("/api/v1/accounts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("계좌 유형이 null이면 400을 반환한다")
        void createAccount_nullType_returns400() throws Exception {
            String json = "{}";

            mockMvc.perform(post("/api/v1/accounts")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("여러 계좌를 개설할 수 있다")
        void createAccount_multipleAccounts() throws Exception {
            // When
            CreateRequest checking = new CreateRequest(Account.AccountType.CHECKING);
            CreateRequest savings = new CreateRequest(Account.AccountType.SAVINGS);

            mockMvc.perform(post("/api/v1/accounts")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(checking)));

            mockMvc.perform(post("/api/v1/accounts")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(savings)));

            // Then
            assertThat(accountRepository.findAll()).hasSize(2);
        }
    }

    // GET /api/v1/accounts — 내 계좌 목록
    @Nested
    @DisplayName("GET /api/v1/accounts")
    class GetMyAccountsApi {

        @Test
        @DisplayName("내 계좌 목록을 반환한다")
        void getMyAccounts_success() throws Exception {
            // Given: 계좌 2개 개설
            createCheckingAccount();
            createCheckingAccount();

            // When & Then
            mockMvc.perform(get("/api/v1/accounts")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].accountId").isNumber())
                    .andExpect(jsonPath("$[0].accountNumber").isNotEmpty())
                    .andExpect(jsonPath("$[0].balance").value(0))
                    .andExpect(jsonPath("$[0].accountType").value("CHECKING"))
                    .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        }

        @Test
        @DisplayName("계좌가 없으면 빈 배열을 반환한다")
        void getMyAccounts_noAccounts_returnsEmpty() throws Exception {
            mockMvc.perform(get("/api/v1/accounts")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("계좌번호가 복호화되어 반환된다")
        void getMyAccounts_accountNumberDecrypted() throws Exception {
            // Given
            createCheckingAccount();

            // When & Then: 계좌번호가 XXX-XX-XXXXXX-X 형식으로 반환
            mockMvc.perform(get("/api/v1/accounts")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].accountNumber").value(
                            org.hamcrest.Matchers.matchesRegex("\\d{3}-\\d{2}-\\d{6}-\\d")));
        }

        @Test
        @DisplayName("인증 없이 조회하면 401을 반환한다")
        void getMyAccounts_withoutToken_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/accounts"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // GET /api/v1/accounts/{id} — 계좌 상세
    @Nested
    @DisplayName("GET /api/v1/accounts/{id}")
    class GetAccountApi {

        @Test
        @DisplayName("계좌 상세 정보를 반환한다")
        void getAccount_success() throws Exception {
            // Given
            Long accountId = createCheckingAccount();

            // When & Then
            mockMvc.perform(get("/api/v1/accounts/{id}", accountId)
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId").value(accountId))
                    .andExpect(jsonPath("$.accountNumber").isNotEmpty())
                    .andExpect(jsonPath("$.balance").value(0))
                    .andExpect(jsonPath("$.accountType").value("CHECKING"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("존재하지 않는 계좌이면 404를 반환한다")
        void getAccount_notFound_returns404() throws Exception {
            mockMvc.perform(get("/api/v1/accounts/{id}", 9999)
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_001"));
        }

        @Test
        @DisplayName("타인의 계좌를 조회하면 403을 반환한다")
        void getAccount_notOwner_returns403() throws Exception {
            // Given: 계좌 개설
            Long accountId = createCheckingAccount();

            // 다른 사용자로 로그인
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

            LoginResponse otherLogin = objectMapper.readValue(
                    result.getResponse().getContentAsString(), LoginResponse.class);

            // When & Then: 다른 사용자의 토큰으로 조회
            mockMvc.perform(get("/api/v1/accounts/{id}", accountId)
                            .header("Authorization", "Bearer " + otherLogin.getAccessToken()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_003"));
        }
    }
}