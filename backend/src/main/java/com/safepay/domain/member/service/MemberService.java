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
    public RefreshResponse refresh(String refreshToken) {
        // JWT 서명/만료 검증
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw new CustomException(ErrorCode.AUTH_TOKEN_INVALID);
        }

        Long memberId = jwtTokenProvider.getMemberId(refreshToken);
        String email = jwtTokenProvider.getEmail(refreshToken);
        String role = jwtTokenProvider.getRole(refreshToken);
        String tokenId = jwtTokenProvider.getTokenId(refreshToken);

        // Redis에서 저장된 JTI 조회
        String storedTokenId = refreshTokenStore.get(memberId);

        if (storedTokenId == null) {
            // 로그아웃된 상태이거나 TTL 만료
            throw new CustomException(ErrorCode.AUTH_REFRESH_TOKEN_NOT_FOUND);
        }

        // Reuse Detection — JTI 불일치 시 탈취 의심
        if (!storedTokenId.equals(tokenId)) {
            log.warn("Refresh Token 재사용 감지! memberId={}, expected={}, actual={}",
                    memberId, storedTokenId, tokenId);
            refreshTokenStore.delete(memberId); // 전체 세션 무효화
            throw new CustomException(ErrorCode.AUTH_TOKEN_REUSE_DETECTED);
        }

        // 새 토큰 쌍 발급 (Rotation)
        String newAccessToken = jwtTokenProvider.createAccessToken(memberId, email, role);
        String newRefreshToken = jwtTokenProvider.createRefreshToken(memberId, email, role);

        // 새 JTI를 Redis에 저장 (기존 덮어씀 → 이전 토큰 자동 무효화)
        String newTokenId = jwtTokenProvider.getTokenId(newRefreshToken);
        refreshTokenStore.save(memberId, newTokenId);

        log.info("토큰 갱신 완료: memberId={}", memberId);

        return new RefreshResponse(newAccessToken, newRefreshToken);
    }

    // 로그아웃 — Redis에서 Refresh Token 삭제
    public void logout(Long memberId) {
        refreshTokenStore.delete(memberId);
        log.info("로그아웃 완료: memberId={}", memberId);
    }
}