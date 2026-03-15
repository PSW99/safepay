package com.safepay.global.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final String code;
    private final String message;
    private final LocalDateTime timestamp;
    private final String detail;

    public static ErrorResponse of(ErrorCode errorCode) {
        return ErrorResponse.builder()
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static ErrorResponse of(ErrorCode errorCode, String detail) {
        return ErrorResponse.builder()
                .code(errorCode.getCode())
                .message(errorCode.getMessage())
                .detail(detail)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
