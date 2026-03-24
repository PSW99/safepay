package com.safepay.domain.member.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class RefreshDto {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RefreshRequest {

        @NotBlank(message = "리프레시 토큰은 필수입니다")
        private String refreshToken;
    }

    @Getter
    @AllArgsConstructor
    public static class RefreshResponse {
        private String accessToken;
        private String refreshToken;
    }
}