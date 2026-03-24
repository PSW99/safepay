package com.safepay.global.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
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

    /**
     * 원자적 토큰 교체 (Lua 스크립트로 GET + 비교 + SET/DEL을 단일 명령으로 실행).
     *
     * <p>경쟁 조건 방지: get() → 비교 → save() 사이에 다른 스레드가 끼어들 수 없다.</p>
     *
     * @return  1  정상 교체 완료 (Rotation 성공)
     *          0  JTI 불일치 → 폐기된 토큰 재사용 감지, Redis 키 자동 삭제 (전체 세션 무효화)
     *         -1  키 없음 → 로그아웃 상태이거나 TTL 만료
     */
    public long rotateToken(Long memberId, String expectedTokenId, String newTokenId) {
        String key = keyPrefix + memberId;
        long ttlSeconds = refreshTokenExpiry / 1000;

        String script = """
                local stored = redis.call('GET', KEYS[1])
                if stored == false then return -1 end
                if stored ~= ARGV[1] then
                    redis.call('DEL', KEYS[1])
                    return 0
                end
                redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
                return 1
                """;

        DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(script, Long.class);
        Long result = redisTemplate.execute(redisScript, List.of(key),
                expectedTokenId, newTokenId, String.valueOf(ttlSeconds));
        return result != null ? result : -1L;
    }
}