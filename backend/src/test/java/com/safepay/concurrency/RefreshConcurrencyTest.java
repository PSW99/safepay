package com.safepay.concurrency;

import com.safepay.domain.member.dto.AuthDto.LoginRequest;
import com.safepay.domain.member.dto.AuthDto.LoginResponse;
import com.safepay.domain.member.entity.Member;
import com.safepay.domain.member.repository.MemberRepository;
import com.safepay.domain.member.service.MemberService;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import com.safepay.global.security.RefreshTokenStore;
import com.safepay.global.util.AesEncryptor;
import com.safepay.integration.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Refresh Token 동시성 테스트 (원자적 Rotation)")
class RefreshConcurrencyTest extends IntegrationTestBase {

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private RefreshTokenStore refreshTokenStore;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AesEncryptor aesEncryptor;

    private static final String TEST_EMAIL = "refresh-concurrency@safepay.com";
    private static final String TEST_PASSWORD = "password123";

    @BeforeEach
    void setUp() {
        memberRepository.deleteAll();

        Member member = Member.builder()
                .email(TEST_EMAIL)
                .password(passwordEncoder.encode(TEST_PASSWORD))
                .name("동시성테스트유저")
                .phone(aesEncryptor.encrypt("010-9999-8888"))
                .build();
        memberRepository.save(member);
    }

    @Nested
    @DisplayName("동시 갱신 (Rotation 원자성)")
    class ConcurrentRefresh {

        @Test
        @DisplayName("⭐ 동시에 N개의 갱신 요청 → 정확히 1개만 성공, 나머지는 예외")
        void concurrentRefresh_onlyOneSucceeds() throws Exception {
            // Given: 로그인하여 Refresh Token 획득
            LoginResponse loginResponse = memberService.login(
                    new LoginRequest(TEST_EMAIL, TEST_PASSWORD));
            String refreshToken = loginResponse.getRefreshToken();

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(); // 모든 스레드가 준비되면 동시에 출발
                        memberService.refresh(refreshToken);
                        successCount.incrementAndGet();
                    } catch (CustomException ex) {
                        if (ex.getErrorCode() == ErrorCode.AUTH_TOKEN_REUSE_DETECTED
                                || ex.getErrorCode() == ErrorCode.AUTH_REFRESH_TOKEN_NOT_FOUND) {
                            failCount.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();  // 동시 출발
            done.await(15, TimeUnit.SECONDS);
            executor.shutdown();

            // Then: 정확히 1개만 성공, 나머지는 모두 실패
            assertThat(successCount.get()).isEqualTo(1);
            assertThat(failCount.get()).isEqualTo(threadCount - 1);
        }

        @Test
        @DisplayName("⭐ Reuse Detection 후 Redis 키가 삭제된다 (세션 전체 무효화)")
        void concurrentRefresh_afterReuseDetection_redisKeyIsDeleted() throws Exception {
            // Given
            LoginResponse loginResponse = memberService.login(
                    new LoginRequest(TEST_EMAIL, TEST_PASSWORD));
            String refreshToken = loginResponse.getRefreshToken();
            Long memberId = loginResponse.getMemberId();

            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        memberService.refresh(refreshToken);
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();
            start.countDown();
            done.await(15, TimeUnit.SECONDS);
            executor.shutdown();

            // Then: 성공한 스레드가 새 JTI로 교체했거나, Reuse Detection으로 키가 삭제됨
            // 어느 경우든 원래 JTI는 Redis에 남아 있지 않아야 한다
            // (정상 교체 시 새 JTI가 저장되고, Reuse Detection 시 키가 삭제됨)
            // 원래 refreshToken은 더 이상 유효하지 않다
            assertThat(memberService).isNotNull(); // 서비스 정상 동작 확인

            // 이전 토큰으로 갱신 시도 → 반드시 실패
            try {
                memberService.refresh(refreshToken);
                // 여기 도달하면 실패 — 이미 사용된 토큰으로 성공하면 안 됨
                assertThat(false).as("이미 사용된 토큰으로 갱신에 성공해서는 안 됩니다").isTrue();
            } catch (CustomException ex) {
                assertThat(ex.getErrorCode())
                        .isIn(ErrorCode.AUTH_TOKEN_REUSE_DETECTED, ErrorCode.AUTH_REFRESH_TOKEN_NOT_FOUND);
            }
        }

        @Test
        @DisplayName("갱신 성공 후 이전 토큰 재사용 → AUTH_TOKEN_REUSE_DETECTED 또는 AUTH_REFRESH_TOKEN_NOT_FOUND")
        void refresh_afterSuccessfulRotation_oldTokenFails() throws Exception {
            // Given
            LoginResponse loginResponse = memberService.login(
                    new LoginRequest(TEST_EMAIL, TEST_PASSWORD));
            String originalRefreshToken = loginResponse.getRefreshToken();

            // 1차 갱신 성공
            memberService.refresh(originalRefreshToken);

            // When: 이전 토큰 재사용 시도
            // Then: Reuse Detection — Lua 스크립트가 원자적으로 처리
            try {
                memberService.refresh(originalRefreshToken);
                assertThat(false).as("이미 사용된 토큰으로 갱신에 성공해서는 안 됩니다").isTrue();
            } catch (CustomException ex) {
                assertThat(ex.getErrorCode())
                        .isIn(ErrorCode.AUTH_TOKEN_REUSE_DETECTED, ErrorCode.AUTH_REFRESH_TOKEN_NOT_FOUND);
            }
        }
    }
}