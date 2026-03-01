package com.safepay.unit.member;

import com.safepay.domain.member.dto.AuthDto.*;
import com.safepay.domain.member.entity.Member;
import com.safepay.domain.member.repository.MemberRepository;
import com.safepay.domain.member.service.MemberService;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import com.safepay.global.security.JwtTokenProvider;
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
}