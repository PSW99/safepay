package com.safepay.global.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Slf4j
@Component
public class HmacUtil {

    private static final String ALGORITHM = "HmacSHA256";

    @Value("${encryption.hmac-key}")
    private String hmacKeyString;

    private SecretKeySpec secretKey;

    @PostConstruct
    public void init() {
        if (hmacKeyString == null || hmacKeyString.isBlank()) {
            throw new IllegalStateException("HMAC 키가 설정되지 않았습니다 (encryption.hmac-key)");
        }
        byte[] rawKey = hmacKeyString.getBytes(StandardCharsets.UTF_8);
        if (rawKey.length < 32) {
            throw new IllegalStateException(
                    "HMAC 키는 32바이트(256bit) 이상이어야 합니다. 현재: " + rawKey.length + "바이트");
        }
        this.secretKey = new SecretKeySpec(rawKey, ALGORITHM);
    }

    // 평문에 대한 HMAC-SHA-256 해시를 생성한다.
    public String hash(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            throw new IllegalArgumentException("HMAC 해시 대상이 null이거나 빈 문자열입니다");
        }

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(secretKey);
            byte[] hashBytes = mac.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (Exception e) {
            log.error("HMAC 해시 생성 실패", e);
            throw new RuntimeException("HMAC 해시 생성에 실패했습니다", e);
        }
    }
}