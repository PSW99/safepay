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
}