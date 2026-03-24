package com.safepay.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safepay.domain.account.repository.AccountRepository;
import com.safepay.domain.member.dto.AuthDto.*;
import com.safepay.domain.member.dto.RefreshDto;
import com.safepay.domain.member.repository.MemberRepository;
import com.safepay.domain.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@DisplayName("Auth API 통합 테스트")
class AuthControllerTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MemberRepository memberRepository;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        memberRepository.deleteAll();
    }

    private SignupRequest createSignupRequest() {
        return new SignupRequest(
                "test@safepay.com",
                "password123",
                "테스트유저",
                "010-1234-5678"
        );
    }

    private void signupMember() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createSignupRequest())))
                .andExpect(status().isCreated());
    }

    // 회원가입 API
    @Nested
    @DisplayName("POST /api/v1/auth/signup")
    class SignupApi {

        @Test
        @DisplayName("정상 회원가입 시 201 Created를 반환한다")
        void signup_success_returns201() throws Exception {
            // Given
            SignupRequest request = createSignupRequest();

            // When & Then
            mockMvc.perform(post("/api/v1/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.memberId").isNumber())
                    .andExpect(jsonPath("$.email").value("test@safepay.com"));
        }

        @Test
        @DisplayName("회원가입 후 DB에 회원이 저장된다")
        void signup_memberIsPersisted() throws Exception {
            // Given
            SignupRequest request = createSignupRequest();

            // When
            mockMvc.perform(post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));

            // Then
            assertThat(memberRepository.existsByEmail("test@safepay.com")).isTrue();
        }

        @Test
        @DisplayName("비밀번호는 평문이 아닌 BCrypt 해시로 저장된다")
        void signup_passwordIsNotPlaintext() throws Exception {
            // Given
            SignupRequest request = createSignupRequest();

            // When
            mockMvc.perform(post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));

            // Then
            var member = memberRepository.findByEmail("test@safepay.com").orElseThrow();
            assertThat(member.getPassword()).isNotEqualTo("password123");
            assertThat(member.getPassword()).startsWith("$2a$"); // BCrypt prefix
        }

        @Test
        @DisplayName("전화번호는 암호화되어 저장된다")
        void signup_phoneIsEncrypted() throws Exception {
            // Given
            SignupRequest request = createSignupRequest();

            // When
            mockMvc.perform(post("/api/v1/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)));

            // Then
            var member = memberRepository.findByEmail("test@safepay.com").orElseThrow();
            assertThat(member.getPhone()).isNotEqualTo("010-1234-5678");
        }

        @Test
        @DisplayName("이메일 중복 시 409 Conflict를 반환한다")
        void signup_duplicateEmail_returns409() throws Exception {
            // Given: 먼저 회원가입
            signupMember();

            // When & Then: 동일 이메일로 재가입
            mockMvc.perform(post("/api/v1/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSignupRequest())))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("AUTH_004"));
        }

        @Test
        @DisplayName("이메일 형식이 잘못되면 400 Bad Request를 반환한다")
        void signup_invalidEmail_returns400() throws Exception {
            // Given
            SignupRequest request = new SignupRequest(
                    "not-an-email",
                    "password123",
                    "테스트유저",
                    "010-1234-5678"
            );

            // When & Then
            mockMvc.perform(post("/api/v1/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("비밀번호가 8자 미만이면 400 Bad Request를 반환한다")
        void signup_shortPassword_returns400() throws Exception {
            // Given
            SignupRequest request = new SignupRequest(
                    "test@safepay.com",
                    "short",
                    "테스트유저",
                    "010-1234-5678"
            );

            // When & Then
            mockMvc.perform(post("/api/v1/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("필수 필드가 비어있으면 400 Bad Request를 반환한다")
        void signup_missingFields_returns400() throws Exception {
            // Given: 이메일만 있고 나머지 비어있음
            String json = """
                    {
                        "email": "test@safepay.com"
                    }
                    """;

            // When & Then
            mockMvc.perform(post("/api/v1/auth/signup")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }
    }

    // 로그인 API
    @Nested
    @DisplayName("POST /api/v1/auth/login")
    class LoginApi {

        @BeforeEach
        void setUp() throws Exception {
            // 매 로그인 테스트 전에 회원가입
            signupMember();
        }

        @Test
        @DisplayName("정상 로그인 시 200 OK와 JWT 토큰을 반환한다")
        void login_success_returns200WithTokens() throws Exception {
            // Given
            LoginRequest request = new LoginRequest("test@safepay.com", "password123");

            // When & Then
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                    .andExpect(jsonPath("$.memberId").isNumber())
                    .andExpect(jsonPath("$.email").value("test@safepay.com"));
        }

        @Test
        @DisplayName("발급된 accessToken으로 인증이 필요한 API에 접근할 수 있다")
        void login_tokenIsUsable() throws Exception {
            // Given: 로그인하여 토큰 획득
            LoginRequest request = new LoginRequest("test@safepay.com", "password123");

            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andReturn();

            String responseBody = result.getResponse().getContentAsString();
            LoginResponse loginResponse = objectMapper.readValue(responseBody, LoginResponse.class);
            String accessToken = loginResponse.getAccessToken();

            // When & Then: 토큰으로 인증 필요 API 호출 (계좌 목록 조회)
            // 401(인증 실패)이 아닌 200(인증 성공)이 오면 인증 성공
            mockMvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .get("/api/v1/accounts")
                                    .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("존재하지 않는 이메일이면 401 Unauthorized를 반환한다")
        void login_wrongEmail_returns401() throws Exception {
            // Given
            LoginRequest request = new LoginRequest("wrong@safepay.com", "password123");

            // When & Then
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_001"));
        }

        @Test
        @DisplayName("비밀번호가 틀리면 401 Unauthorized를 반환한다")
        void login_wrongPassword_returns401() throws Exception {
            // Given
            LoginRequest request = new LoginRequest("test@safepay.com", "wrongPassword");

            // When & Then
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_001"));
        }

        @Test
        @DisplayName("이메일 없음과 비밀번호 틀림은 동일한 에러 코드를 반환한다 (보안)")
        void login_sameErrorForEmailAndPassword() throws Exception {
            // Case 1: 이메일 없음
            MvcResult result1 = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new LoginRequest("wrong@safepay.com", "password123"))))
                    .andReturn();

            // Case 2: 비밀번호 틀림
            MvcResult result2 = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new LoginRequest("test@safepay.com", "wrongPassword"))))
                    .andReturn();

            // Then: 동일한 HTTP 상태코드 & 에러 코드 (계정 존재 여부 노출 방지)
            assertThat(result1.getResponse().getStatus())
                    .isEqualTo(result2.getResponse().getStatus());

            String body1 = result1.getResponse().getContentAsString();
            String body2 = result2.getResponse().getContentAsString();
            assertThat(objectMapper.readTree(body1).get("code").asText())
                    .isEqualTo("AUTH_001");
            assertThat(objectMapper.readTree(body2).get("code").asText())
                    .isEqualTo("AUTH_001");
        }

        @Test
        @DisplayName("인증 없이 보호된 API에 접근하면 401을 반환한다")
        void accessProtectedApi_withoutToken_returns401() throws Exception {
            mockMvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .get("/api/v1/accounts"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // 토큰 갱신 API
    @Nested
    @DisplayName("POST /api/v1/auth/refresh")
    class RefreshApi {

        private String refreshToken;

        @BeforeEach
        void setUp() throws Exception {
            // 회원가입 + 로그인하여 Refresh Token 획득
            signupMember();

            LoginRequest loginRequest = new LoginRequest("test@safepay.com", "password123");
            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andReturn();

            LoginResponse loginResponse = objectMapper.readValue(
                    result.getResponse().getContentAsString(), LoginResponse.class);
            refreshToken = loginResponse.getRefreshToken();
        }

        @Test
        @DisplayName("정상 갱신 시 200 OK와 새 토큰 쌍을 반환한다")
        void refresh_success_returns200() throws Exception {
            // Given
            RefreshDto.RefreshRequest request = new RefreshDto.RefreshRequest(refreshToken);

            // When & Then
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.refreshToken").isNotEmpty());
        }

        @Test
        @DisplayName("갱신된 Access Token으로 인증 API에 접근할 수 있다")
        void refresh_newAccessToken_isUsable() throws Exception {
            // Given: 갱신
            RefreshDto.RefreshRequest request = new RefreshDto.RefreshRequest(refreshToken);
            MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andReturn();

            RefreshDto.RefreshResponse refreshResponse = objectMapper.readValue(
                    result.getResponse().getContentAsString(), RefreshDto.RefreshResponse.class);

            // When & Then: 새 Access Token으로 보호된 API 호출
            mockMvc.perform(get("/api/v1/accounts")
                            .header("Authorization", "Bearer " + refreshResponse.getAccessToken()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("⭐ 갱신 후 이전 Refresh Token으로 재갱신하면 401을 반환한다 (Rotation)")
        void refresh_oldToken_returns401() throws Exception {
            // Given: 1차 갱신 성공
            RefreshDto.RefreshRequest firstRequest = new RefreshDto.RefreshRequest(refreshToken);
            mockMvc.perform(post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(firstRequest)))
                    .andExpect(status().isOk());

            // When & Then: 이전 토큰으로 2차 갱신 → 실패 (Reuse Detection)
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(firstRequest)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_005"));
        }

        @Test
        @DisplayName("⭐ Reuse Detection 후 새 토큰으로도 갱신 불가 (전체 세션 무효화)")
        void refresh_afterReuseDetection_newTokenAlsoFails() throws Exception {
            // Given: 1차 갱신 → 새 토큰 획득
            RefreshDto.RefreshRequest firstRequest = new RefreshDto.RefreshRequest(refreshToken);
            MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(firstRequest)))
                    .andReturn();

            RefreshDto.RefreshResponse firstResponse = objectMapper.readValue(
                    result.getResponse().getContentAsString(), RefreshDto.RefreshResponse.class);
            String newRefreshToken = firstResponse.getRefreshToken();

            // 이전 토큰으로 갱신 → Reuse Detection → 전체 세션 무효화
            mockMvc.perform(post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(firstRequest)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_005"));

            // When & Then: 새 토큰으로도 갱신 불가 (세션 전체가 무효화됨)
            RefreshDto.RefreshRequest newRequest = new RefreshDto.RefreshRequest(newRefreshToken);
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(newRequest)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_006"));
        }

        @Test
        @DisplayName("잘못된 토큰으로 갱신하면 401을 반환한다")
        void refresh_invalidToken_returns401() throws Exception {
            // Given
            RefreshDto.RefreshRequest request = new RefreshDto.RefreshRequest("invalid.jwt.token");

            // When & Then
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_003"));
        }

        @Test
        @DisplayName("Refresh Token 없이 요청하면 400을 반환한다")
        void refresh_noToken_returns400() throws Exception {
            // Given
            String json = "{}";

            // When & Then
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isBadRequest());
        }
    }

    // 로그아웃 API
    @Nested
    @DisplayName("POST /api/v1/auth/logout")
    class LogoutApi {

        private String accessToken;
        private String refreshToken;

        @BeforeEach
        void setUp() throws Exception {
            // 회원가입 + 로그인
            signupMember();

            LoginRequest loginRequest = new LoginRequest("test@safepay.com", "password123");
            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andReturn();

            LoginResponse loginResponse = objectMapper.readValue(
                    result.getResponse().getContentAsString(), LoginResponse.class);
            accessToken = loginResponse.getAccessToken();
            refreshToken = loginResponse.getRefreshToken();
        }

        @Test
        @DisplayName("정상 로그아웃 시 204 No Content를 반환한다")
        void logout_success_returns204() throws Exception {
            mockMvc.perform(post("/api/v1/auth/logout")
                            .header("Authorization", "Bearer " + accessToken))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("⭐ 로그아웃 후 Refresh Token으로 갱신하면 401을 반환한다")
        void logout_thenRefresh_returns401() throws Exception {
            // Given: 로그아웃
            mockMvc.perform(post("/api/v1/auth/logout")
                    .header("Authorization", "Bearer " + accessToken));

            // When & Then: 갱신 시도 → 실패
            RefreshDto.RefreshRequest request = new RefreshDto.RefreshRequest(refreshToken);
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_006"));
        }

        @Test
        @DisplayName("인증 없이 로그아웃하면 401을 반환한다")
        void logout_withoutToken_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/auth/logout"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("로그아웃 후 재로그인하면 새 토큰으로 정상 동작한다")
        void logout_thenRelogin_success() throws Exception {
            // Given: 로그아웃
            mockMvc.perform(post("/api/v1/auth/logout")
                    .header("Authorization", "Bearer " + accessToken));

            // When: 재로그인
            LoginRequest loginRequest = new LoginRequest("test@safepay.com", "password123");
            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginRequest)))
                    .andReturn();

            LoginResponse newLogin = objectMapper.readValue(
                    result.getResponse().getContentAsString(), LoginResponse.class);

            // Then: 새 토큰으로 갱신 성공
            RefreshDto.RefreshRequest refreshRequest = new RefreshDto.RefreshRequest(newLogin.getRefreshToken());
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(refreshRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty());
        }
    }
}