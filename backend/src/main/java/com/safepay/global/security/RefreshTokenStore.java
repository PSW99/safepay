package com.safepay.global.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;


@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private final StringRedisTemplate redisTemplate;

    @Value("${refresh-token.prefix}")
    private String keyPrefix;

    @Value("${jwt.refresh-token-expiry}")
    private long refreshTokenExpiry;

    // Refresh Token JTI를 Redis에 저장한다.
    public void save(Long memberId, String tokenId) {
        String key = keyPrefix + memberId;
        long ttlSeconds = refreshTokenExpiry / 1000;
        redisTemplate.opsForValue().set(key, tokenId, ttlSeconds, TimeUnit.SECONDS);
        log.debug("Refresh Token 저장: memberId={}, jti={}", memberId, tokenId);
    }

    // 저장된 Refresh Token JTI를 조회한다.
    public String get(Long memberId) {
        String key = keyPrefix + memberId;
        return redisTemplate.opsForValue().get(key);
    }

    // Refresh Token을 삭제한다 (로그아웃 또는 Reuse Detection 시).
    public void delete(Long memberId) {
        String key = keyPrefix + memberId;
        redisTemplate.delete(key);
        log.debug("Refresh Token 삭제: memberId={}", memberId);
    }
}
