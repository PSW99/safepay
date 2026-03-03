package com.safepay.domain.account.dto;

import com.safepay.domain.account.entity.Account;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class AccountDto {

    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {

        @NotNull(message = "계좌 유형은 필수입니다")
        private Account.AccountType accountType;
    }

    @Getter
    @AllArgsConstructor
    public static class CreateResponse {
        private Long accountId;
        private String accountNumber; // 복호화된 계좌번호
    }

    @Getter
    @AllArgsConstructor
    public static class AccountResponse {
        private Long accountId;
        private String accountNumber;
        private BigDecimal balance;
        private String accountType;
        private String status;
        private LocalDateTime createdAt;

        public static AccountResponse from(Account account, String decryptedNumber) {
            return new AccountResponse(
                    account.getId(),
                    decryptedNumber,
                    account.getBalance(),
                    account.getAccountType().name(),
                    account.getStatus().name(),
                    account.getCreatedAt()
            );
        }
    }
}
