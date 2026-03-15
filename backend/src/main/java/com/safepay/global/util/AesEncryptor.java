package com.safepay.global.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Component
public class AesEncryptor {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    @Value("${encryption.aes-key}")
    private String aesKeyString;

    private SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @PostConstruct
    public void init() {
        if (aesKeyString == null || aesKeyString.isBlank()) {
            throw new IllegalStateException("AES 암호화 키가 설정되지 않았습니다 (encryption.aes-key)");
        }
        byte[] rawKey = aesKeyString.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (rawKey.length < 32) {
            throw new IllegalStateException(
                    "AES 키는 32바이트(256bit) 이상이어야 합니다. 현재: " + rawKey.length + "바이트");
        }
        this.secretKey = new SecretKeySpec(rawKey, 0, 32, ALGORITHM);
    }

    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // IV + 암호문을 합쳐서 Base64 인코딩
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + encryptedBytes.length);
            byteBuffer.put(iv);
            byteBuffer.put(encryptedBytes);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new RuntimeException("암호화에 실패했습니다", e);
        }
    }

    public String decrypt(String encryptedText) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptedText);
            if (decoded.length < GCM_IV_LENGTH + 16) {
                throw new IllegalArgumentException("암호문이 너무 짧습니다 (최소 " + (GCM_IV_LENGTH + 16) + "바이트 필요)");
            }
            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);

            byte[] cipherText = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            return new String(cipher.doFinal(cipherText), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new RuntimeException("복호화에 실패했습니다", e);
        }
    }
}
