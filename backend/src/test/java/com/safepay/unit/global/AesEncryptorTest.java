package com.safepay.unit.global;

import com.safepay.global
        .util.AesEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AesEncryptor 단위 테스트")
class AesEncryptorTest {

    private AesEncryptor aesEncryptor;

    @BeforeEach
    void setUp() {
        aesEncryptor = new AesEncryptor();
        ReflectionTestUtils.setField(aesEncryptor, "aesKeyString", "safepay-test-aes-256-key!!-padding");
        aesEncryptor.init();
    }

    @Test
    @DisplayName("암호화 후 복호화하면 원문과 같다")
    void encrypt_decrypt_roundtrip() {
        // Given
        String plainText = "010-1234-5678";

        // When
        String encrypted = aesEncryptor.encrypt(plainText);
        String decrypted = aesEncryptor.decrypt(encrypted);

        // Then
        assertThat(decrypted).isEqualTo(plainText);
    }

    @Test
    @DisplayName("같은 평문이라도 매번 다른 암호문이 생성된다 (IV가 랜덤)")
    void encrypt_sameInput_differentOutput() {
        // Given
        String plainText = "010-1234-5678";

        // When
        String encrypted1 = aesEncryptor.encrypt(plainText);
        String encrypted2 = aesEncryptor.encrypt(plainText);

        // Then
        assertThat(encrypted1).isNotEqualTo(encrypted2);
    }

    @Test
    @DisplayName("암호문이 변조되면 복호화에 실패한다 (GCM 인증 태그 검증)")
    void decrypt_tampered_throwsException() {
        // Given
        String encrypted = aesEncryptor.encrypt("원본 데이터");
        String tampered = encrypted.substring(0, encrypted.length() - 2) + "XX";

        // When & Then
        assertThatThrownBy(() -> aesEncryptor.decrypt(tampered))
                .isInstanceOf(RuntimeException.class);
    }
}
