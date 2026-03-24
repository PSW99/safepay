package com.safepay.global.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
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

    // Lua script: 비교와 교체를 단일 원자 연산으로 수행한다.
    // 반환값: 1 = 성공, 0 = JTI 불일치(재사용 감지), -1 = 키 없음(로그아웃/만료)
    private static final DefaultRedisScript<Long> COMPARE_AND_ROTATE_SCRIPT;

    static {
        COMPARE_AND_ROTATE_SCRIPT = new DefaultRedisScript<>();
        COMPARE_AND_ROTATE_SCRIPT.setScriptText(
                "local current = redis.call('GET', KEYS[1]) " +
                "if current == false then return -1 end " +
                "if current ~= ARGV[1] then " +
                "  redis.call('DEL', KEYS[1]) " +
                "  return 0 " +
                "end " +
                "redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]) " +
                "return 1"
        );
        COMPARE_AND_ROTATE_SCRIPT.setResultType(Long.class);
    }

    // Refresh Token JTI를 Redis에 저장한다.
    public void save(Long memberId, String tokenId) {
        String key = keyPrefix + memberId;
        long ttlSeconds = refreshTokenExpiry / 1000;
        redisTemplate.opsForValue().set(key, tokenId, ttlSeconds, TimeUnit.SECONDS);
        log.debug("Refresh Token 저장: memberId={}, jti={}...", memberId, maskJti(tokenId));
    }

    // 저장된 Refresh Token JTI를 조회한다.
    public String get(Long memberId) {
        String key = keyPrefix + memberId;
        return redisTemplate.opsForValue().get(key);
    }

    // Refresh Token을 삭제한다 (로그아웃 시).
    public void delete(Long memberId) {
        String key = keyPrefix + memberId;
        redisTemplate.delete(key);
        log.debug("Refresh Token 삭제: memberId={}", memberId);
    }

    /**
     * 원자적 비교-교체 (Compare-And-Rotate).
     * 저장된 JTI가 expectedJti와 일치하면 newJti로 교체하고,
     * 불일치하면 키를 삭제(전체 세션 무효화)한다.
     *
     * @return 1 = 성공, 0 = JTI 불일치(재사용 감지 → 키 삭제됨), -1 = 키 없음
     */
    public long compareAndRotate(Long memberId, String expectedJti, String newJti) {
        String key = keyPrefix + memberId;
        long ttlSeconds = refreshTokenExpiry / 1000;
        Long result = redisTemplate.execute(
                COMPARE_AND_ROTATE_SCRIPT,
                Collections.singletonList(key),
                expectedJti, newJti, String.valueOf(ttlSeconds)
        );
        log.debug("Refresh Token 로테이션: memberId={}, result={}", memberId, result);
        return result != null ? result : -1;
    }

    private String maskJti(String jti) {
        if (jti == null || jti.length() <= 8) return "***";
        return jti.substring(0, 8) + "...";
    }
}
