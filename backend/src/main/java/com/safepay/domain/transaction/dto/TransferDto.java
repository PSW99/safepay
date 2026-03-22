package com.safepay.domain.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TransferDto {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransferRequest {

        @NotNull(message = "송금 대상 계좌 ID는 필수입니다")
        private Long toAccountId;

        @NotNull(message = "금액은 필수입니다")
        @DecimalMin(value = "0.01", message = "금액은 0보다 커야 합니다")
        private BigDecimal amount;

        private String description;
    }

    @Getter
    @AllArgsConstructor
    public static class TransferResponse {
        private Long fromTransactionId;
        private Long toTransactionId;
        private BigDecimal amount;
        private BigDecimal fromBalanceAfter;
        private BigDecimal toBalanceAfter;
        private String status;
        private LocalDateTime createdAt;
    }
}