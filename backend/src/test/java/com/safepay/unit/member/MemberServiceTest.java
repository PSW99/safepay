package com.safepay.unit.member;

import com.safepay.domain.member.dto.AuthDto.*;
import com.safepay.domain.member.dto.RefreshDto;
import com.safepay.domain.member.entity.Member;
import com.safepay.domain.member.repository.MemberRepository;
import com.safepay.domain.member.service.MemberService;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import com.safepay.global.security.JwtTokenProvider;
import com.safepay.global.security.RefreshTokenStore;
import com.safepay.global.util.AesEncryptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemberService 단위 테스트")
class MemberServiceTest {

    @InjectMocks
    private MemberService memberService;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private AesEncryptor aesEncryptor;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    private SignupRequest createSignupRequest() {
        return new SignupRequest(
                "test@safepay.com",
                "password123",
                "테스트유저",
                "010-1234-5678"
        );
    }

    private LoginRequest createLoginRequest() {
        return new LoginRequest("test@safepay.com", "password123");
    }

    private Member createMember() {
        Member member = Member.builder()
                .email("test@safepay.com")
                .password("$2a$10$encodedPassword")
                .name("테스트유저")
                .phone("encryptedPhone")
                .build();
        ReflectionTestUtils.setField(member, "id", 1L);
        return member;
    }

    // 회원가입
    @Nested
    @DisplayName("회원가입")
    class Signup {

        @Test
        @DisplayName("정상 회원가입 시 memberId와 email을 반환한다")
        void signup_success() {
            // Given
            SignupRequest request = createSignupRequest();
            Member savedMember = createMember();

            given(memberRepository.existsByEmail(request.getEmail())).willReturn(false);
            given(passwordEncoder.encode(request.getPassword())).willReturn("$2a$10$encodedPassword");
            given(aesEncryptor.encrypt(request.getPhone())).willReturn("encryptedPhone");
            given(memberRepository.save(any(Member.class))).willReturn(savedMember);

            // When
            SignupResponse response = memberService.signup(request);

            // Then
            assertThat(response.getMemberId()).isEqualTo(1L);
            assertThat(response.getEmail()).isEqualTo("test@safepay.com");
        }

        @Test
        @DisplayName("비밀번호가 BCrypt로 해싱되어 저장된다")
        void signup_passwordIsHashed() {
            // Given
            SignupRequest request = createSignupRequest();
            Member savedMember = createMember();

            given(memberRepository.existsByEmail(anyString())).willReturn(false);
            given(passwordEncoder.encode("password123")).willReturn("$2a$10$hashedPassword");
            given(aesEncryptor.encrypt(anyString())).willReturn("encryptedPhone");
            given(memberRepository.save(any(Member.class))).willReturn(savedMember);

            // When
            memberService.signup(request);

            // Then
            verify(passwordEncoder).encode("password123");
        }

        @Test
        @DisplayName("전화번호가 AES-256으로 암호화되어 저장된다")
        void signup_phoneIsEncrypted() {
            // Given
            SignupRequest request = createSignupRequest();
            Member savedMember = createMember();

            given(memberRepository.existsByEmail(anyString())).willReturn(false);
            given(passwordEncoder.encode(anyString())).willReturn("$2a$10$encoded");
            given(aesEncryptor.encrypt("010-1234-5678")).willReturn("encryptedPhone");
            given(memberRepository.save(any(Member.class))).willReturn(savedMember);

            // When
            memberService.signup(request);

            // Then
            verify(aesEncryptor).encrypt("010-1234-5678");
        }

        @Test
        @DisplayName("이미 가입된 이메일이면 AUTH_DUPLICATE_EMAIL 예외가 발생한다")
        void signup_duplicateEmail_throwsException() {
            // Given
            SignupRequest request = createSignupRequest();
            given(memberRepository.existsByEmail(request.getEmail())).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> memberService.signup(request))
                    .isInstanceOf(CustomException.class)
                    .satisfies(exception -> {
                        CustomException ex = (CustomException) exception;
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AUTH_DUPLICATE_EMAIL);
                    });

            // 중복이면 save가 호출되지 않아야 한다
            verify(memberRepository, never()).save(any());
        }
    }

    // 로그인
    @Nested
    @DisplayName("로그인")
    class Login {

        @Test
        @DisplayName("정상 로그인 시 accessToken과 refreshToken을 반환한다")
        void login_success() {
            // Given
            LoginRequest request = createLoginRequest();
            Member member = createMember();

            given(memberRepository.findByEmail(request.getEmail())).willReturn(Optional.of(member));
            given(passwordEncoder.matches("password123", member.getPassword())).willReturn(true);
            given(jwtTokenProvider.createAccessToken(1L, "test@safepay.com", "USER"))
                    .willReturn("access-token-value");
            given(jwtTokenProvider.createRefreshToken(1L, "test@safepay.com", "USER"))
                    .willReturn("refresh-token-value");

            // When
            LoginResponse response = memberService.login(request);

            // Then
            assertThat(response.getAccessToken()).isEqualTo("access-token-value");
            assertThat(response.getRefreshToken()).isEqualTo("refresh-token-value");
            assertThat(response.getMemberId()).isEqualTo(1L);
            assertThat(response.getEmail()).isEqualTo("test@safepay.com");
        }

        @Test
        @DisplayName("존재하지 않는 이메일이면 AUTH_INVALID_CREDENTIALS 예외가 발생한다")
        void login_emailNotFound_throwsException() {
            // Given
            LoginRequest request = createLoginRequest();
            given(memberRepository.findByEmail(request.getEmail())).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> memberService.login(request))
                    .isInstanceOf(CustomException.class)
                    .satisfies(exception -> {
                        CustomException ex = (CustomException) exception;
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
                    });
        }

        @Test
        @DisplayName("비밀번호가 틀리면 AUTH_INVALID_CREDENTIALS 예외가 발생한다")
        void login_wrongPassword_throwsException() {
            // Given
            LoginRequest request = createLoginRequest();
            Member member = createMember();

            given(memberRepository.findByEmail(request.getEmail())).willReturn(Optional.of(member));
            given(passwordEncoder.matches("password123", member.getPassword())).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> memberService.login(request))
                    .isInstanceOf(CustomException.class)
                    .satisfies(exception -> {
                        CustomException ex = (CustomException) exception;
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
                    });

            // 비밀번호 틀리면 토큰 생성이 호출되지 않아야 한다
            verify(jwtTokenProvider, never()).createAccessToken(any(), any(), any());
        }

        @Test
        @DisplayName("이메일 없음과 비밀번호 틀림은 동일한 에러 코드를 반환한다 (보안)")
        void login_emailNotFound_and_wrongPassword_sameErrorCode() {
            // 보안 원칙: 공격자가 이메일 존재 여부를 알 수 없도록
            // 이메일이 없든, 비밀번호가 틀리든 같은 에러 메시지를 반환해야 한다

            // Case 1: 이메일 없음
            given(memberRepository.findByEmail("wrong@email.com")).willReturn(Optional.empty());

            CustomException ex1 = null;
            try {
                memberService.login(new LoginRequest("wrong@email.com", "password123"));
            } catch (CustomException e) {
                ex1 = e;
            }

            // Case 2: 비밀번호 틀림
            Member member = createMember();
            given(memberRepository.findByEmail("test@safepay.com")).willReturn(Optional.of(member));
            given(passwordEncoder.matches("wrongPassword", member.getPassword())).willReturn(false);

            CustomException ex2 = null;
            try {
                memberService.login(new LoginRequest("test@safepay.com", "wrongPassword"));
            } catch (CustomException e) {
                ex2 = e;
            }

            // Then: 동일한 에러 코드
            assertThat(ex1).isNotNull();
            assertThat(ex2).isNotNull();
            assertThat(ex1.getErrorCode()).isEqualTo(ex2.getErrorCode());
            assertThat(ex1.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
    }

    // 토큰 갱신 (Refresh Token Rotation)
    @Nested
    @DisplayName("토큰 갱신")
    class Refresh {

        @Test
        @DisplayName("정상 갱신 시 새 Access Token + Refresh Token을 반환한다")
        void refresh_success() {
            // Given
            String oldRefreshToken = "old-refresh-token";
            given(jwtTokenProvider.validateToken(oldRefreshToken)).willReturn(true);
            given(jwtTokenProvider.getMemberId(oldRefreshToken)).willReturn(1L);
            given(jwtTokenProvider.getEmail(oldRefreshToken)).willReturn("test@safepay.com");
            given(jwtTokenProvider.getRole(oldRefreshToken)).willReturn("USER");
            given(jwtTokenProvider.getTokenId(oldRefreshToken)).willReturn("jti-v1");

            given(jwtTokenProvider.createAccessToken(1L, "test@safepay.com", "USER"))
                    .willReturn("new-access-token");
            given(jwtTokenProvider.createRefreshToken(1L, "test@safepay.com", "USER"))
                    .willReturn("new-refresh-token");
            given(jwtTokenProvider.getTokenId("new-refresh-token")).willReturn("jti-v2");
            given(refreshTokenStore.compareAndRotate(1L, "jti-v1", "jti-v2")).willReturn(1L);

            // When
            RefreshDto.RefreshResponse response = memberService.refresh(oldRefreshToken);

            // Then
            assertThat(response.getAccessToken()).isEqualTo("new-access-token");
            assertThat(response.getRefreshToken()).isEqualTo("new-refresh-token");
        }

        @Test
        @DisplayName("갱신 시 원자적 비교-교체(compareAndRotate)가 호출된다")
        void refresh_usesAtomicCompareAndRotate() {
            // Given
            String oldRefreshToken = "old-refresh-token";
            given(jwtTokenProvider.validateToken(oldRefreshToken)).willReturn(true);
            given(jwtTokenProvider.getMemberId(oldRefreshToken)).willReturn(1L);
            given(jwtTokenProvider.getEmail(oldRefreshToken)).willReturn("test@safepay.com");
            given(jwtTokenProvider.getRole(oldRefreshToken)).willReturn("USER");
            given(jwtTokenProvider.getTokenId(oldRefreshToken)).willReturn("jti-v1");

            given(jwtTokenProvider.createAccessToken(any(), any(), any())).willReturn("new-at");
            given(jwtTokenProvider.createRefreshToken(any(), any(), any())).willReturn("new-rt");
            given(jwtTokenProvider.getTokenId("new-rt")).willReturn("jti-v2");
            given(refreshTokenStore.compareAndRotate(1L, "jti-v1", "jti-v2")).willReturn(1L);

            // When
            memberService.refresh(oldRefreshToken);

            // Then: 원자적 CAS가 호출되고, 분리된 get/save는 호출되지 않는다
            verify(refreshTokenStore).compareAndRotate(1L, "jti-v1", "jti-v2");
            verify(refreshTokenStore, never()).get(any());
            verify(refreshTokenStore, never()).save(any(), any());
        }

        @Test
        @DisplayName("만료된 토큰으로 갱신 시 AUTH_TOKEN_INVALID 예외가 발생한다")
        void refresh_expiredToken_throwsException() {
            // Given
            given(jwtTokenProvider.validateToken("expired-token")).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> memberService.refresh("expired-token"))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.AUTH_TOKEN_INVALID));
        }

        @Test
        @DisplayName("JTI가 null이면 Redis 조회 없이 AUTH_TOKEN_INVALID 예외가 발생한다")
        void refresh_nullJti_throwsException() {
            // Given: Access Token처럼 JTI가 없는 토큰
            given(jwtTokenProvider.validateToken("access-token")).willReturn(true);
            given(jwtTokenProvider.getMemberId("access-token")).willReturn(1L);
            given(jwtTokenProvider.getTokenId("access-token")).willReturn(null);

            // When & Then
            assertThatThrownBy(() -> memberService.refresh("access-token"))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.AUTH_TOKEN_INVALID));

            // Redis에 접근하지 않아야 한다
            verify(refreshTokenStore, never()).compareAndRotate(any(), any(), any());
            verify(refreshTokenStore, never()).get(any());
        }

        @Test
        @DisplayName("Redis에 토큰이 없으면 AUTH_REFRESH_TOKEN_NOT_FOUND 예외가 발생한다")
        void refresh_noTokenInRedis_throwsException() {
            // Given: 로그아웃된 상태
            String refreshToken = "valid-token";
            given(jwtTokenProvider.validateToken(refreshToken)).willReturn(true);
            given(jwtTokenProvider.getMemberId(refreshToken)).willReturn(1L);
            given(jwtTokenProvider.getEmail(refreshToken)).willReturn("test@safepay.com");
            given(jwtTokenProvider.getRole(refreshToken)).willReturn("USER");
            given(jwtTokenProvider.getTokenId(refreshToken)).willReturn("jti-v1");
            given(jwtTokenProvider.createAccessToken(any(), any(), any())).willReturn("at");
            given(jwtTokenProvider.createRefreshToken(any(), any(), any())).willReturn("rt");
            given(jwtTokenProvider.getTokenId("rt")).willReturn("jti-v2");
            given(refreshTokenStore.compareAndRotate(1L, "jti-v1", "jti-v2")).willReturn(-1L);

            // When & Then
            assertThatThrownBy(() -> memberService.refresh(refreshToken))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.AUTH_REFRESH_TOKEN_NOT_FOUND));
        }
    }

    // Reuse Detection (탈취 감지)
    @Nested
    @DisplayName("Reuse Detection")
    class ReuseDetection {

        @Test
        @DisplayName("⭐ 폐기된 토큰으로 갱신 시 AUTH_TOKEN_REUSE_DETECTED (Lua script가 세션 무효화)")
        void refresh_reuseDetected_invalidatesAllSessions() {
            // Given: Redis에 jti-v2가 저장된 상태에서 jti-v1으로 갱신 시도
            String oldRefreshToken = "old-refresh-token";
            given(jwtTokenProvider.validateToken(oldRefreshToken)).willReturn(true);
            given(jwtTokenProvider.getMemberId(oldRefreshToken)).willReturn(1L);
            given(jwtTokenProvider.getEmail(oldRefreshToken)).willReturn("test@safepay.com");
            given(jwtTokenProvider.getRole(oldRefreshToken)).willReturn("USER");
            given(jwtTokenProvider.getTokenId(oldRefreshToken)).willReturn("jti-v1");
            given(jwtTokenProvider.createAccessToken(any(), any(), any())).willReturn("at");
            given(jwtTokenProvider.createRefreshToken(any(), any(), any())).willReturn("rt");
            given(jwtTokenProvider.getTokenId("rt")).willReturn("jti-new");
            given(refreshTokenStore.compareAndRotate(1L, "jti-v1", "jti-new")).willReturn(0L);

            // When & Then
            assertThatThrownBy(() -> memberService.refresh(oldRefreshToken))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.AUTH_TOKEN_REUSE_DETECTED));
        }

        @Test
        @DisplayName("Reuse Detection 후 정상 토큰도 갱신 불가 (전체 무효화)")
        void refresh_afterReuseDetection_validTokenAlsoFails() {
            // 시나리오: 공격자가 v1로 갱신 → Lua script가 키 삭제 → 정상 유저의 v2도 실패

            // 1단계: 공격자의 v1 갱신 시도 → Reuse Detection (Lua script가 키 삭제)
            String attackerToken = "attacker-token";
            given(jwtTokenProvider.validateToken(attackerToken)).willReturn(true);
            given(jwtTokenProvider.getMemberId(attackerToken)).willReturn(1L);
            given(jwtTokenProvider.getEmail(attackerToken)).willReturn("test@safepay.com");
            given(jwtTokenProvider.getRole(attackerToken)).willReturn("USER");
            given(jwtTokenProvider.getTokenId(attackerToken)).willReturn("jti-v1");
            given(jwtTokenProvider.createAccessToken(any(), any(), any())).willReturn("at");
            given(jwtTokenProvider.createRefreshToken(any(), any(), any())).willReturn("rt");
            given(jwtTokenProvider.getTokenId("rt")).willReturn("jti-new");
            given(refreshTokenStore.compareAndRotate(1L, "jti-v1", "jti-new")).willReturn(0L);

            try {
                memberService.refresh(attackerToken);
            } catch (CustomException ignored) {}

            // 2단계: 정상 유저의 v2 갱신 시도 → Lua script가 키 없음 반환
            String validToken = "valid-token";
            given(jwtTokenProvider.validateToken(validToken)).willReturn(true);
            given(jwtTokenProvider.getMemberId(validToken)).willReturn(1L);
            given(jwtTokenProvider.getEmail(validToken)).willReturn("test@safepay.com");
            given(jwtTokenProvider.getRole(validToken)).willReturn("USER");
            given(jwtTokenProvider.getTokenId(validToken)).willReturn("jti-v2");
            given(jwtTokenProvider.createAccessToken(any(), any(), any())).willReturn("at2");
            given(jwtTokenProvider.createRefreshToken(any(), any(), any())).willReturn("rt2");
            given(jwtTokenProvider.getTokenId("rt2")).willReturn("jti-new2");
            given(refreshTokenStore.compareAndRotate(1L, "jti-v2", "jti-new2")).willReturn(-1L);

            assertThatThrownBy(() -> memberService.refresh(validToken))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.AUTH_REFRESH_TOKEN_NOT_FOUND));
        }
    }

    // 로그인 시 Redis 저장
    @Nested
    @DisplayName("로그인 시 Refresh Token 저장")
    class LoginRefreshToken {

        @Test
        @DisplayName("로그인 성공 시 Refresh Token JTI가 Redis에 저장된다")
        void login_savesRefreshTokenToRedis() {
            // Given
            LoginRequest request = createLoginRequest();
            Member member = createMember();

            given(memberRepository.findByEmail(request.getEmail())).willReturn(Optional.of(member));
            given(passwordEncoder.matches("password123", member.getPassword())).willReturn(true);
            given(jwtTokenProvider.createAccessToken(1L, "test@safepay.com", "USER"))
                    .willReturn("access-token");
            given(jwtTokenProvider.createRefreshToken(1L, "test@safepay.com", "USER"))
                    .willReturn("refresh-token");
            given(jwtTokenProvider.getTokenId("refresh-token")).willReturn("jti-new");

            // When
            memberService.login(request);

            // Then
            verify(refreshTokenStore).save(1L, "jti-new");
        }
    }

    // 로그아웃
    @Nested
    @DisplayName("로그아웃")
    class Logout {

        @Test
        @DisplayName("로그아웃 시 Redis에서 Refresh Token이 삭제된다")
        void logout_deletesFromRedis() {
            // When
            memberService.logout(1L);

            // Then
            verify(refreshTokenStore).delete(1L);
        }

        @Test
        @DisplayName("로그아웃 후 갱신 시도 → AUTH_REFRESH_TOKEN_NOT_FOUND")
        void logout_thenRefresh_fails() {
            // Given: 로그아웃
            memberService.logout(1L);

            // 갱신 시도
            String refreshToken = "some-token";
            given(jwtTokenProvider.validateToken(refreshToken)).willReturn(true);
            given(jwtTokenProvider.getMemberId(refreshToken)).willReturn(1L);
            given(jwtTokenProvider.getEmail(refreshToken)).willReturn("test@safepay.com");
            given(jwtTokenProvider.getRole(refreshToken)).willReturn("USER");
            given(jwtTokenProvider.getTokenId(refreshToken)).willReturn("jti-old");
            given(jwtTokenProvider.createAccessToken(any(), any(), any())).willReturn("at");
            given(jwtTokenProvider.createRefreshToken(any(), any(), any())).willReturn("rt");
            given(jwtTokenProvider.getTokenId("rt")).willReturn("jti-new");
            given(refreshTokenStore.compareAndRotate(1L, "jti-old", "jti-new")).willReturn(-1L);

            // When & Then
            assertThatThrownBy(() -> memberService.refresh(refreshToken))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.AUTH_REFRESH_TOKEN_NOT_FOUND));
        }
    }
}