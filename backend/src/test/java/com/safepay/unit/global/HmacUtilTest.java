package com.safepay.unit.global;

import com.safepay.global.util.HmacUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("HmacUtil 단위 테스트")
class HmacUtilTest {

    private HmacUtil hmacUtil;

    @BeforeEach
    void setUp() {
        hmacUtil = new HmacUtil();
        ReflectionTestUtils.setField(hmacUtil, "hmacKeyString",
                "safepay-test-hmac-256-key!!-pad!!");
        hmacUtil.init();
    }

    @Nested
    @DisplayName("해시 생성")
    class Hash {

        @Test
        @DisplayName("동일 입력은 항상 동일한 해시를 반환한다 (결정론적)")
        void hash_sameInput_sameOutput() {
            // Given
            String plainText = "100-01-123456-7";

            // When
            String hash1 = hmacUtil.hash(plainText);
            String hash2 = hmacUtil.hash(plainText);

            // Then
            assertThat(hash1).isEqualTo(hash2);
        }

        @Test
        @DisplayName("다른 입력은 다른 해시를 반환한다")
        void hash_differentInput_differentOutput() {
            // Given
            String plainText1 = "100-01-123456-7";
            String plainText2 = "100-01-654321-3";

            // When
            String hash1 = hmacUtil.hash(plainText1);
            String hash2 = hmacUtil.hash(plainText2);

            // Then
            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("해시 길이는 64자이다 (SHA-256 = 256bit = 64 hex chars)")
        void hash_length_is64() {
            // When
            String hash = hmacUtil.hash("100-01-123456-7");

            // Then
            assertThat(hash).hasSize(64);
        }

        @Test
        @DisplayName("해시는 소문자 hex 문자열이다")
        void hash_isLowercaseHex() {
            // When
            String hash = hmacUtil.hash("100-01-123456-7");

            // Then
            assertThat(hash).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("미세한 입력 차이도 완전히 다른 해시를 생성한다")
        void hash_smallChange_completelyDifferentHash() {
            // Given: 마지막 숫자만 다름
            String hash1 = hmacUtil.hash("100-01-123456-7");
            String hash2 = hmacUtil.hash("100-01-123456-8");

            // Then: 완전히 다른 해시 (avalanche effect)
            assertThat(hash1).isNotEqualTo(hash2);
        }
    }

    @Nested
    @DisplayName("입력 검증")
    class InputValidation {

        @Test
        @DisplayName("null 입력 시 IllegalArgumentException이 발생한다")
        void hash_null_throwsException() {
            assertThatThrownBy(() -> hmacUtil.hash(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("빈 문자열 입력 시 IllegalArgumentException이 발생한다")
        void hash_empty_throwsException() {
            assertThatThrownBy(() -> hmacUtil.hash(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("공백 문자열 입력 시 IllegalArgumentException이 발생한다")
        void hash_blank_throwsException() {
            assertThatThrownBy(() -> hmacUtil.hash("   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("키 검증")
    class KeyValidation {

        @Test
        @DisplayName("키가 32바이트 미만이면 초기화 시 예외가 발생한다")
        void init_shortKey_throwsException() {
            // Given
            HmacUtil shortKeyUtil = new HmacUtil();
            ReflectionTestUtils.setField(shortKeyUtil, "hmacKeyString", "too-short");

            // When & Then
            assertThatThrownBy(shortKeyUtil::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32바이트");
        }

        @Test
        @DisplayName("키가 null이면 초기화 시 예외가 발생한다")
        void init_nullKey_throwsException() {
            // Given
            HmacUtil nullKeyUtil = new HmacUtil();
            ReflectionTestUtils.setField(nullKeyUtil, "hmacKeyString", null);

            // When & Then
            assertThatThrownBy(nullKeyUtil::init)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("다른 키로는 다른 해시가 생성된다")
        void hash_differentKey_differentOutput() {
            // Given
            HmacUtil otherUtil = new HmacUtil();
            ReflectionTestUtils.setField(otherUtil, "hmacKeyString",
                    "another-hmac-key-for-testing-32b!");
            otherUtil.init();

            String plainText = "100-01-123456-7";

            // When
            String hash1 = hmacUtil.hash(plainText);
            String hash2 = otherUtil.hash(plainText);

            // Then
            assertThat(hash1).isNotEqualTo(hash2);
        }
    }
}