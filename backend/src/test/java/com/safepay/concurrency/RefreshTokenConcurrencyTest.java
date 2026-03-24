package com.safepay.concurrency;

import com.safepay.domain.member.dto.RefreshDto;
import com.safepay.domain.member.entity.Member;
import com.safepay.domain.member.repository.MemberRepository;
import com.safepay.domain.member.service.MemberService;
import com.safepay.global.exception.CustomException;
import com.safepay.global.security.JwtTokenProvider;
import com.safepay.global.security.RefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Refresh Token Rotation 동시성 테스트")
class RefreshTokenConcurrencyTest extends ConcurrencyTestBase {

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private RefreshTokenStore refreshTokenStore;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String refreshToken;

    @BeforeEach
    void setUp() {
        memberRepository.deleteAll();

        Member member = Member.builder()
                .email("concurrent@safepay.com")
                .password(passwordEncoder.encode("password123"))
                .name("동시성테스트")
                .phone("encrypted-phone")
                .build();
        memberRepository.save(member);

        var loginResponse = memberService.login(
                new com.safepay.domain.member.dto.AuthDto.LoginRequest(
                        "concurrent@safepay.com", "password123"));
        refreshToken = loginResponse.getRefreshToken();
    }

    @Test
    @DisplayName("동일 Refresh Token으로 동시 갱신 시 정확히 1건만 성공한다")
    void concurrentRefresh_onlyOneSucceeds() throws InterruptedException {
        // Given
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        // When: 모든 스레드가 동시에 같은 refresh token으로 갱신 시도
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    memberService.refresh(refreshToken);
                    successCount.incrementAndGet();
                } catch (CustomException e) {
                    failCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        ready.await();
        start.countDown(); // 동시 시작

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Then: 정확히 1건만 성공, 나머지는 실패
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(threadCount - 1);
    }
}
