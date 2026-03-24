package com.safepay.unit.global;

import com.safepay.global.security.RefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshTokenStore 단위 테스트")
class RefreshTokenStoreTest {

    private RefreshTokenStore refreshTokenStore;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        refreshTokenStore = new RefreshTokenStore(redisTemplate);
        ReflectionTestUtils.setField(refreshTokenStore, "keyPrefix", "safepay:refresh:");
        ReflectionTestUtils.setField(refreshTokenStore, "refreshTokenExpiry", 604800000L); // 7일
    }

    @Nested
    @DisplayName("저장")
    class Save {

        @Test
        @DisplayName("JTI를 Redis에 저장한다")
        void save_storesTokenId() {
            // Given
            given(redisTemplate.opsForValue()).willReturn(valueOperations);

            // When
            refreshTokenStore.save(1L, "jti-abc123");

            // Then
            verify(valueOperations).set(
                    eq("safepay:refresh:1"),
                    eq("jti-abc123"),
                    eq(604800L),
                    eq(TimeUnit.SECONDS)
            );
        }

        @Test
        @DisplayName("TTL이 Refresh Token 만료 시간과 동일하다 (7일 = 604800초)")
        void save_ttlMatchesExpiry() {
            // Given
            given(redisTemplate.opsForValue()).willReturn(valueOperations);

            // When
            refreshTokenStore.save(1L, "jti-abc123");

            // Then: 604800000ms / 1000 = 604800초
            ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
            verify(valueOperations).set(anyString(), anyString(), ttlCaptor.capture(), any(TimeUnit.class));
            assertThat(ttlCaptor.getValue()).isEqualTo(604800L);
        }

        @Test
        @DisplayName("2번 저장하면 마지막 값으로 덮어쓴다 (Rotation)")
        void save_overwritesPrevious() {
            // Given
            given(redisTemplate.opsForValue()).willReturn(valueOperations);

            // When
            refreshTokenStore.save(1L, "jti-v1");
            refreshTokenStore.save(1L, "jti-v2");

            // Then: 같은 키에 2번 SET
            verify(valueOperations).set(eq("safepay:refresh:1"), eq("jti-v1"), anyLong(), any());
            verify(valueOperations).set(eq("safepay:refresh:1"), eq("jti-v2"), anyLong(), any());
        }
    }

    @Nested
    @DisplayName("조회")
    class Get {

        @Test
        @DisplayName("저장된 JTI를 반환한다")
        void get_returnsStoredTokenId() {
            // Given
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get("safepay:refresh:1")).willReturn("jti-abc123");

            // When
            String result = refreshTokenStore.get(1L);

            // Then
            assertThat(result).isEqualTo("jti-abc123");
        }

        @Test
        @DisplayName("저장된 값이 없으면 null을 반환한다")
        void get_notFound_returnsNull() {
            // Given
            given(redisTemplate.opsForValue()).willReturn(valueOperations);
            given(valueOperations.get("safepay:refresh:999")).willReturn(null);

            // When
            String result = refreshTokenStore.get(999L);

            // Then
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("삭제")
    class Delete {

        @Test
        @DisplayName("Redis에서 키를 삭제한다")
        void delete_removesKey() {
            // When
            refreshTokenStore.delete(1L);

            // Then
            verify(redisTemplate).delete("safepay:refresh:1");
        }
    }

    @Nested
    @DisplayName("원자적 교체 (rotateToken)")
    class RotateToken {

        @Test
        @DisplayName("JTI 일치 시 1을 반환한다 (Rotation 성공)")
        void rotateToken_match_returns1() {
            // Given
            given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                    .willReturn(1L);

            // When
            long result = refreshTokenStore.rotateToken(1L, "jti-v1", "jti-v2");

            // Then
            assertThat(result).isEqualTo(1L);
        }

        @Test
        @DisplayName("JTI 불일치 시 0을 반환한다 (Reuse Detection — Lua 스크립트가 키 자동 삭제)")
        void rotateToken_mismatch_returns0() {
            // Given
            given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                    .willReturn(0L);

            // When
            long result = refreshTokenStore.rotateToken(1L, "jti-old", "jti-new");

            // Then
            assertThat(result).isEqualTo(0L);
        }

        @Test
        @DisplayName("키 없음 시 -1을 반환한다 (로그아웃 또는 TTL 만료)")
        void rotateToken_keyNotFound_returnsMinus1() {
            // Given
            given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                    .willReturn(-1L);

            // When
            long result = refreshTokenStore.rotateToken(1L, "jti-v1", "jti-v2");

            // Then
            assertThat(result).isEqualTo(-1L);
        }

        @Test
        @DisplayName("execute가 null을 반환하면 -1로 처리한다")
        void rotateToken_nullResult_returnsMinus1() {
            // Given
            given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                    .willReturn(null);

            // When
            long result = refreshTokenStore.rotateToken(1L, "jti-v1", "jti-v2");

            // Then
            assertThat(result).isEqualTo(-1L);
        }

        @Test
        @DisplayName("올바른 Redis 키와 인자로 Lua 스크립트를 실행한다")
        void rotateToken_usesCorrectKeyAndArgs() {
            // Given
            given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                    .willReturn(1L);

            // When
            refreshTokenStore.rotateToken(42L, "expected-jti", "new-jti");

            // Then: key = "safepay:refresh:42"
            ArgumentCaptor<List> keyCaptor = ArgumentCaptor.forClass(List.class);
            verify(redisTemplate).execute(any(RedisScript.class), keyCaptor.capture(),
                    eq("expected-jti"), eq("new-jti"), eq("604800"));
            assertThat(keyCaptor.getValue()).containsExactly("safepay:refresh:42");
        }
    }
}