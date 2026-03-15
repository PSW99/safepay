package com.safepay.unit.global;

import com.safepay.global.security.JwtTokenProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtTokenProvider 단위 테스트")
class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    private static final String SECRET = "safepay-test-jwt-secret-key-must-be-at-least-256-bits-long-for-hmac-sha";
    private static final long ACCESS_TOKEN_EXPIRY = 1800000L;  // 30분
    private static final long REFRESH_TOKEN_EXPIRY = 604800000L; // 7일

    private static final Long MEMBER_ID = 1L;
    private static final String EMAIL = "test@safepay.com";
    private static final String ROLE = "USER";

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "secret", SECRET);
        ReflectionTestUtils.setField(jwtTokenProvider, "accessTokenExpiry", ACCESS_TOKEN_EXPIRY);
        ReflectionTestUtils.setField(jwtTokenProvider, "refreshTokenExpiry", REFRESH_TOKEN_EXPIRY);
        jwtTokenProvider.init();
    }

    @Nested
    @DisplayName("토큰 생성")
    class CreateToken {

        @Test
        @DisplayName("Access Token이 정상적으로 생성된다")
        void createAccessToken_success() {
            // When
            String token = jwtTokenProvider.createAccessToken(MEMBER_ID, EMAIL, ROLE);

            // Then
            assertThat(token).isNotNull();
            assertThat(token.split("\\.")).hasSize(3); // header.payload.signature
        }

        @Test
        @DisplayName("Refresh Token이 정상적으로 생성된다")
        void createRefreshToken_success() {
            // When
            String token = jwtTokenProvider.createRefreshToken(MEMBER_ID, EMAIL, ROLE);

            // Then
            assertThat(token).isNotNull();
            assertThat(token.split("\\.")).hasSize(3);
        }

        @Test
        @DisplayName("Access Token과 Refresh Token은 서로 다르다")
        void accessAndRefreshToken_areDifferent() {
            // When
            String accessToken = jwtTokenProvider.createAccessToken(MEMBER_ID, EMAIL, ROLE);
            String refreshToken = jwtTokenProvider.createRefreshToken(MEMBER_ID, EMAIL, ROLE);

            // Then
            assertThat(accessToken).isNotEqualTo(refreshToken);
        }
    }

    @Nested
    @DisplayName("클레임 파싱")
    class ParseClaims {

        @Test
        @DisplayName("토큰에서 memberId를 추출할 수 있다")
        void getMemberId_success() {
            // Given
            String token = jwtTokenProvider.createAccessToken(MEMBER_ID, EMAIL, ROLE);

            // When
            Long memberId = jwtTokenProvider.getMemberId(token);

            // Then
            assertThat(memberId).isEqualTo(MEMBER_ID);
        }

        @Test
        @DisplayName("토큰에서 email을 추출할 수 있다")
        void getEmail_success() {
            // Given
            String token = jwtTokenProvider.createAccessToken(MEMBER_ID, EMAIL, ROLE);

            // When
            String email = jwtTokenProvider.getEmail(token);

            // Then
            assertThat(email).isEqualTo(EMAIL);
        }

        @Test
        @DisplayName("토큰에서 role을 추출할 수 있다")
        void getRole_success() {
            // Given
            String token = jwtTokenProvider.createAccessToken(MEMBER_ID, EMAIL, ROLE);

            // When
            String role = jwtTokenProvider.getRole(token);

            // Then
            assertThat(role).isEqualTo(ROLE);
        }
    }

    @Nested
    @DisplayName("토큰 검증")
    class ValidateToken {

        @Test
        @DisplayName("유효한 토큰은 true를 반환한다")
        void validateToken_validToken_returnsTrue() {
            // Given
            String token = jwtTokenProvider.createAccessToken(MEMBER_ID, EMAIL, ROLE);

            // When & Then
            assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        }

        @Test
        @DisplayName("만료된 토큰은 false를 반환한다")
        void validateToken_expiredToken_returnsFalse() {
            // Given: 만료 시간을 -1ms로 설정하여 즉시 만료되는 토큰 생성
            JwtTokenProvider expiredProvider = new JwtTokenProvider();
            ReflectionTestUtils.setField(expiredProvider, "secret", SECRET);
            ReflectionTestUtils.setField(expiredProvider, "accessTokenExpiry", -1L);
            ReflectionTestUtils.setField(expiredProvider, "refreshTokenExpiry", REFRESH_TOKEN_EXPIRY);
            expiredProvider.init();

            String expiredToken = expiredProvider.createAccessToken(MEMBER_ID, EMAIL, ROLE);

            // When & Then
            assertThat(jwtTokenProvider.validateToken(expiredToken)).isFalse();
        }

        @Test
        @DisplayName("잘못된 형식의 토큰은 false를 반환한다")
        void validateToken_malformedToken_returnsFalse() {
            // Given
            String malformedToken = "this.is.not-a-valid-jwt";

            // When & Then
            assertThat(jwtTokenProvider.validateToken(malformedToken)).isFalse();
        }

        @Test
        @DisplayName("다른 시크릿으로 서명된 토큰은 false를 반환한다")
        void validateToken_differentSecret_returnsFalse() {
            // Given: 다른 시크릿 키로 토큰 생성
            String differentSecret = "another-secret-key-that-is-at-least-256-bits-long-for-hmac-sha-signing";
            String tamperedToken = Jwts.builder()
                    .subject(String.valueOf(MEMBER_ID))
                    .claim("email", EMAIL)
                    .claim("role", ROLE)
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + ACCESS_TOKEN_EXPIRY))
                    .signWith(Keys.hmacShaKeyFor(differentSecret.getBytes(StandardCharsets.UTF_8)))
                    .compact();

            // When & Then
            assertThat(jwtTokenProvider.validateToken(tamperedToken)).isFalse();
        }

        @Test
        @DisplayName("빈 문자열은 false를 반환한다")
        void validateToken_emptyString_returnsFalse() {
            // When & Then
            assertThat(jwtTokenProvider.validateToken("")).isFalse();
        }
    }
}