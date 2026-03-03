package com.safepay.domain.transaction.dto;

import com.safepay.domain.transaction.entity.Transaction;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TransactionDto {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DepositRequest {

        @NotNull(message = "금액은 필수입니다")
        @DecimalMin(value = "0.01", message = "금액은 0보다 커야 합니다")
        private BigDecimal amount;

        private String description;
    }

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WithdrawRequest {

        @NotNull(message = "금액은 필수입니다")
        @DecimalMin(value = "0.01", message = "금액은 0보다 커야 합니다")
        private BigDecimal amount;

        private String description;
    }

    @Getter
    @AllArgsConstructor
    public static class TransactionResponse {
        private Long transactionId;
        private String type;
        private BigDecimal amount;
        private BigDecimal balanceAfter;
        private String description;
        private String status;
        private LocalDateTime createdAt;

        public static TransactionResponse from(Transaction tx) {
            return new TransactionResponse(
                    tx.getId(),
                    tx.getType().name(),
                    tx.getAmount(),
                    tx.getBalanceAfter(),
                    tx.getDescription(),
                    tx.getStatus().name(),
                    tx.getCreatedAt()
            );
        }
    }
}
