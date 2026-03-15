package com.safepay.global.util;

import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class DistributedLockManager {

    private final RedissonClient redissonClient;

    @Value("${distributed-lock.key-prefix}")
    private String keyPrefix;

    @Value("${distributed-lock.wait-time}")
    private long waitTime;

    @Value("${distributed-lock.lease-time}")
    private long leaseTime;

    /**
     * 분산 락 획득
     *
     * @param accountId 계좌 ID
     * @return RLock 객체 (finally에서 unlock 호출용)
     * @throws CustomException CONCURRENCY_CONFLICT — 락 획득 실패 시
     */
    public RLock lock(Long accountId) {
        String key = keyPrefix + accountId;
        RLock lock = redissonClient.getLock(key);

        try {
            boolean acquired = lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("분산 락 획득 실패: key={}, waitTime={}s", key, waitTime);
                throw new CustomException(ErrorCode.CONCURRENCY_CONFLICT);
            }
            log.debug("분산 락 획득: key={}", key);
            return lock;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("분산 락 획득 중 인터럽트: key={}", key, e);
            throw new CustomException(ErrorCode.CONCURRENCY_CONFLICT);
        }
    }

    /**
     * 분산 락 해제
     *
     * @param lock lock() 메서드에서 반환된 RLock 객체
     */
    public void unlock(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
            log.debug("분산 락 해제: key={}", lock.getName());
        }
    }

    /**
     * 분산 락 획득 시도
     *
     * @param accountId 계좌 ID
     * @return RLock (획득 성공 시), null (Redis 장애 시)
     */
    public RLock tryLockOrNull(Long accountId) {
        try {
            return lock(accountId);
        } catch (Exception e) {
            if (e instanceof CustomException) {
                throw (CustomException) e;
            }
            log.warn("Redis 분산 락 사용 불가, 비관적 락만으로 진행: accountId={}, error={}",
                    accountId, e.getMessage());
            return null;
        }
    }
}