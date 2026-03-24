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

        // 새 토큰 쌍 먼저 생성
        String newAccessToken = jwtTokenProvider.createAccessToken(memberId, email, role);
        String newRefreshToken = jwtTokenProvider.createRefreshToken(memberId, email, role);
        String newTokenId = jwtTokenProvider.getTokenId(newRefreshToken);

        // 원자적 CAS: 기존 JTI와 일치할 때만 새 JTI로 교체 (경쟁 조건 방지)
        long result = refreshTokenStore.rotateToken(memberId, tokenId, newTokenId);

        if (result == -1L) {
            // 로그아웃된 상태이거나 TTL 만료
            throw new CustomException(ErrorCode.AUTH_REFRESH_TOKEN_NOT_FOUND);
        }

        if (result == 0L) {
            // Reuse Detection — 폐기된 토큰 재사용 감지, Redis 키는 Lua 스크립트에서 이미 삭제됨
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