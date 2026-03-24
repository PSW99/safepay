package com.safepay.domain.member.controller;

import com.safepay.domain.member.dto.AuthDto.LoginRequest;
import com.safepay.domain.member.dto.AuthDto.LoginResponse;
import com.safepay.domain.member.dto.AuthDto.SignupRequest;
import com.safepay.domain.member.dto.AuthDto.SignupResponse;
import com.safepay.domain.member.dto.RefreshDto.RefreshRequest;
import com.safepay.domain.member.dto.RefreshDto.RefreshResponse;
import com.safepay.domain.member.service.MemberService;
import com.safepay.global.security.CustomUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final MemberService memberService;

    @Operation(summary = "회원가입")
    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(memberService.signup(request));
    }

    @Operation(summary = "로그인")
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(memberService.login(request));
    }

    @Operation(summary = "토큰 갱신", description = "Refresh Token으로 새 Access Token + Refresh Token 발급 (Rotation)")
    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(memberService.refresh(request.getRefreshToken()));
    }

    @Operation(summary = "로그아웃", description = "Refresh Token을 Redis에서 삭제하여 즉시 무효화")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal CustomUserPrincipal principal) {
        memberService.logout(principal.getMemberId());
        return ResponseEntity.noContent().build();
    }
}
