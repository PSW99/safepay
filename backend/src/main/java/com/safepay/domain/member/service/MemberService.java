package com.safepay.domain.member.service;

import com.safepay.domain.member.dto.AuthDto.*;
import com.safepay.domain.member.entity.Member;
import com.safepay.domain.member.repository.MemberRepository;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import com.safepay.global.security.JwtTokenProvider;
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

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        // 이메일 중복 체크
        if (memberRepository.existsByEmail(request.getEmail())) {
            throw new CustomException(ErrorCode.AUTH_DUPLICATE_EMAIL);
        }

        // 회원 생성 (비밀번호 해싱, 전화번호 암호화)
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
        // 이메일로 회원 조회
        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.AUTH_INVALID_CREDENTIALS));

        // 비밀번호 검증
        if (!passwordEncoder.matches(request.getPassword(), member.getPassword())) {
            throw new CustomException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }

        // JWT 토큰 생성
        String accessToken = jwtTokenProvider.createAccessToken(
                member.getId(), member.getEmail(), member.getRole().name());
        String refreshToken = jwtTokenProvider.createRefreshToken(
                member.getId(), member.getEmail(), member.getRole().name());

        log.info("로그인 성공: memberId={}", member.getId());

        return new LoginResponse(accessToken, refreshToken, member.getId(), member.getEmail());
    }
}
