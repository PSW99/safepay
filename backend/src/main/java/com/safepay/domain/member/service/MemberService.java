package com.safepay.domain.member.service;

import com.safepay.domain.member.dto.AuthDto.*;
import com.safepay.domain.member.dto.RefreshDto.*;
import com.safepay.domain.member.entity.Member;
import com.safepay.domain.member.repository.MemberRepository;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import com.safepay.global.security.JwtTokenProvider;
import com.safepay.global.security.RefreshTokenStore;
import com.safepay.global.util.AesEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AesEncryptor aesEncryptor;
    private final RefreshTokenStore refreshTokenStore;

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (memberRepository.existsByEmail(request.getEmail())) {
            throw new CustomException(ErrorCode.AUTH_DUPLICATE_EMAIL);
        }

        Member member = Member.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .phone(aesEncryptor.encrypt(request.getPhone()))
                .build();

        try {
            Member saved = memberRepository.save(member);
            log.info("회원 가입 완료: memberId={}", saved.getId());
            return new SignupResponse(saved.getId(), saved.getEmail());
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.AUTH_DUPLICATE_EMAIL);
        }
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.getPassword(), member.getPassword())) {
            throw new CustomException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        String accessToken = jwtTokenProvider.createAccessToken(
                member.getId(), member.getEmail(), member.getRole().name());
        String refreshToken = jwtTokenProvider.createRefreshToken(
                member.getId(), member.getEmail(), member.getRole().name());

        // Refresh Token JTI를 Redis에 저장
        String tokenId = jwtTokenProvider.getTokenId(refreshToken);
        refreshTokenStore.save(member.getId(), tokenId);

        log.info("로그인 성공: memberId={}", member.getId());

        return new LoginResponse(accessToken, refreshToken, member.getId(), member.getEmail());
    }

    // Refresh Token Rotation — 토큰 갱신
    @Transactional(readOnly = true)
    public RefreshResponse refresh(String refreshToken) {
        // JWT 서명/만료 검증
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new CustomException(ErrorCode.AUTH_TOKEN_INVALID);
        }

        Long memberId = jwtTokenProvider.getMemberId(refreshToken);
        String tokenId = jwtTokenProvider.getTokenId(refreshToken);

        // JTI가 없는 토큰(Access Token 등)은 즉시 거절
        if (tokenId == null) {
            throw new CustomException(ErrorCode.AUTH_TOKEN_INVALID);
        }

        // DB에서 현재 회원 상태를 조회하여 최신 권한으로 토큰 발급
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_MEMBER_NOT_FOUND));

        // 새 토큰 쌍 발급 (CAS 전에 생성 — 실패 시 버려짐)
        String newAccessToken = jwtTokenProvider.createAccessToken(
                member.getId(), member.getEmail(), member.getRole().name());
        String newRefreshToken = jwtTokenProvider.createRefreshToken(
                member.getId(), member.getEmail(), member.getRole().name());
        String newTokenId = jwtTokenProvider.getTokenId(newRefreshToken);

        // 원자적 비교-교체 (Lua script): get → 비교 → save를 단일 연산으로 수행
        long result = refreshTokenStore.compareAndRotate(memberId, tokenId, newTokenId);

        if (result == -1) {
            throw new CustomException(ErrorCode.AUTH_REFRESH_TOKEN_NOT_FOUND);
        }
        if (result == 0) {
            log.warn("Refresh Token 재사용 감지! memberId={}", memberId);
            throw new CustomException(ErrorCode.AUTH_TOKEN_REUSE_DETECTED);
        }

        log.info("토큰 갱신 완료: memberId={}", memberId);

        return new RefreshResponse(newAccessToken, newRefreshToken);
    }

    // 로그아웃 — Redis에서 Refresh Token 삭제
    public void logout(Long memberId) {
        refreshTokenStore.delete(memberId);
        log.info("로그아웃 완료: memberId={}", memberId);
    }
}