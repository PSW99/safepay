package com.safepay.unit;

import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import com.safepay.global.util.DistributedLockManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("DistributedLockManager 단위 테스트")
class DistributedLockManagerTest {

    private DistributedLockManager lockManager;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    @BeforeEach
    void setUp() {
        lockManager = new DistributedLockManager(redissonClient);
        ReflectionTestUtils.setField(lockManager, "keyPrefix", "safepay:lock:account:");
        ReflectionTestUtils.setField(lockManager, "waitTime", 3L);
        ReflectionTestUtils.setField(lockManager, "leaseTime", 5L);
    }

    @Nested
    @DisplayName("락 획득")
    class Lock {

        @Test
        @DisplayName("정상적으로 분산 락을 획득한다")
        void lock_success() throws InterruptedException {
            // Given
            given(redissonClient.getLock("safepay:lock:account:1")).willReturn(rLock);
            given(rLock.tryLock(3L, 5L, TimeUnit.SECONDS)).willReturn(true);

            // When
            RLock result = lockManager.lock(1L);

            // Then
            assertThat(result).isEqualTo(rLock);
            verify(rLock).tryLock(3L, 5L, TimeUnit.SECONDS);
        }

        @Test
        @DisplayName("락 획득 실패 시 CONCURRENCY_CONFLICT 예외가 발생한다")
        void lock_timeout_throwsException() throws InterruptedException {
            // Given
            given(redissonClient.getLock("safepay:lock:account:1")).willReturn(rLock);
            given(rLock.tryLock(3L, 5L, TimeUnit.SECONDS)).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> lockManager.lock(1L))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));
        }

        @Test
        @DisplayName("인터럽트 발생 시 CONCURRENCY_CONFLICT 예외가 발생한다")
        void lock_interrupted_throwsException() throws InterruptedException {
            // Given
            given(redissonClient.getLock("safepay:lock:account:1")).willReturn(rLock);
            given(rLock.tryLock(3L, 5L, TimeUnit.SECONDS)).willThrow(new InterruptedException());

            // When & Then
            assertThatThrownBy(() -> lockManager.lock(1L))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));
        }

        @Test
        @DisplayName("계좌별로 독립적인 락 키를 사용한다")
        void lock_differentAccounts_differentKeys() throws InterruptedException {
            // Given
            given(redissonClient.getLock("safepay:lock:account:1")).willReturn(rLock);
            given(redissonClient.getLock("safepay:lock:account:2")).willReturn(rLock);
            given(rLock.tryLock(anyLong(), anyLong(), any())).willReturn(true);

            // When
            lockManager.lock(1L);
            lockManager.lock(2L);

            // Then
            verify(redissonClient).getLock("safepay:lock:account:1");
            verify(redissonClient).getLock("safepay:lock:account:2");
        }
    }

    @Nested
    @DisplayName("락 해제")
    class Unlock {

        @Test
        @DisplayName("현재 스레드가 보유한 락을 해제한다")
        void unlock_success() {
            // Given
            given(rLock.isHeldByCurrentThread()).willReturn(true);

            // When
            lockManager.unlock(rLock);

            // Then
            verify(rLock).unlock();
        }

        @Test
        @DisplayName("현재 스레드가 보유하지 않은 락은 해제하지 않는다")
        void unlock_notHeldByCurrentThread_doesNothing() {
            // Given
            given(rLock.isHeldByCurrentThread()).willReturn(false);

            // When
            lockManager.unlock(rLock);

            // Then
            verify(rLock, never()).unlock();
        }

        @Test
        @DisplayName("null 락을 해제해도 예외가 발생하지 않는다")
        void unlock_null_doesNothing() {
            // When & Then: 예외 없이 무시
            lockManager.unlock(null);
        }
    }

    @Nested
    @DisplayName("Graceful Degradation (tryLockOrNull)")
    class TryLockOrNull {

        @Test
        @DisplayName("정상 시 락을 반환한다")
        void tryLockOrNull_success() throws InterruptedException {
            // Given
            given(redissonClient.getLock(anyString())).willReturn(rLock);
            given(rLock.tryLock(anyLong(), anyLong(), any())).willReturn(true);

            // When
            RLock result = lockManager.tryLockOrNull(1L);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Redis 연결 실패 시 null을 반환한다 (비관적 락만으로 진행)")
        void tryLockOrNull_redisDown_returnsNull() {
            // Given: Redis 연결 자체가 실패 (인프라 예외)
            given(redissonClient.getLock(anyString()))
                    .willThrow(new RedisConnectionException("Redis connection refused"));

            // When
            RLock result = lockManager.tryLockOrNull(1L);

            // Then: null 반환 (폴백 — 비관적 락만으로 동작)
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("코드 오류(비인프라 예외)는 그대로 전파한다")
        void tryLockOrNull_unexpectedException_propagates() {
            // Given: 인프라 장애가 아닌 코드 버그
            given(redissonClient.getLock(anyString()))
                    .willThrow(new NullPointerException("unexpected null"));

            // When & Then: 폴백하지 않고 그대로 전파
            assertThatThrownBy(() -> lockManager.tryLockOrNull(1L))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("CONCURRENCY_CONFLICT 예외는 그대로 전파한다")
        void tryLockOrNull_concurrencyConflict_propagates() throws InterruptedException {
            // Given: 락 획득 타임아웃 (이건 Redis가 살아있는 상태에서 경합)
            given(redissonClient.getLock(anyString())).willReturn(rLock);
            given(rLock.tryLock(anyLong(), anyLong(), any())).willReturn(false);

            // When & Then: CONCURRENCY_CONFLICT는 폴백하면 안 됨 (진짜 경합 상황)
            assertThatThrownBy(() -> lockManager.tryLockOrNull(1L))
                    .isInstanceOf(CustomException.class)
                    .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));
        }
    }
}