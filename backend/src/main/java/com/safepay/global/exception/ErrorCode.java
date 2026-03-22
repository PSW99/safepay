package com.safepay.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Auth
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_001", "이메일 또는 비밀번호가 올바르지 않습니다"),
    AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_002", "토큰이 만료되었습니다"),
    AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_003", "유효하지 않은 토큰입니다"),
    AUTH_DUPLICATE_EMAIL(HttpStatus.CONFLICT, "AUTH_004", "이미 가입된 이메일입니다"),

    // Account
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "ACCOUNT_001", "계좌를 찾을 수 없습니다"),
    ACCOUNT_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "ACCOUNT_002", "비활성 상태의 계좌입니다"),
    ACCOUNT_NOT_OWNER(HttpStatus.FORBIDDEN, "ACCOUNT_003", "본인의 계좌가 아닙니다"),

    // Transaction
    INSUFFICIENT_BALANCE(HttpStatus.BAD_REQUEST, "TX_001", "잔액이 부족합니다"),
    DUPLICATE_TRANSACTION(HttpStatus.CONFLICT, "TX_002", "이미 처리된 요청입니다"),
    CONCURRENCY_CONFLICT(HttpStatus.CONFLICT, "TX_003", "동시 요청 충돌이 발생했습니다. 잠시 후 재시도해주세요"),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "TX_004", "유효하지 않은 금액입니다"),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "TX_005", "Idempotency-Key는 UUID v4 형식이어야 합니다"),
    SELF_TRANSFER(HttpStatus.BAD_REQUEST, "TX_006", "자기 자신에게 송금할 수 없습니다"),
    TRANSFER_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "TX_007", "송금 대상 계좌를 찾을 수 없습니다"),

    // Common
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_001", "서버 내부 오류가 발생했습니다"),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_002", "입력값이 올바르지 않습니다");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
