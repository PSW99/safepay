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

        Member saved = memberRepository.save(member);
        log.info("회원 가입 완료: memberId={}, email={}", saved.getId(), saved.getEmail());

        return new SignupResponse(saved.getId(), saved.getEmail());
    }
}
