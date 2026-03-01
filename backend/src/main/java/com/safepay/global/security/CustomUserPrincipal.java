package com.safepay.global.security;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CustomUserPrincipal {

    private final Long memberId;
    private final String email;
    private final String role;
}
