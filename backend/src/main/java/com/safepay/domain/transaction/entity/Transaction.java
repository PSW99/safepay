package com.safepay.domain.transaction.entity;

import com.safepay.domain.account.entity.Account;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transaction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 15, scale = 2)
    private BigDecimal balanceAfter;

    private String description;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 36)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Transaction(Account account, TransactionType type, BigDecimal amount,
                       BigDecimal balanceAfter, String description,
                       String idempotencyKey, TransactionStatus status) {
        this.account = account;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.description = description;
        this.idempotencyKey = idempotencyKey;
        this.status = status != null ? status : TransactionStatus.SUCCESS;
    }

    // 팩토리 메서드
    public static Transaction createDeposit(Account account, BigDecimal amount,
                                             String description, String idempotencyKey) {
        return Transaction.builder()
                .account(account)
                .type(TransactionType.DEPOSIT)
                .amount(amount)
                .balanceAfter(account.getBalance())
                .description(description)
                .idempotencyKey(idempotencyKey)
                .status(TransactionStatus.SUCCESS)
                .build();
    }

    public static Transaction createWithdraw(Account account, BigDecimal amount,
                                              String description, String idempotencyKey) {
        return Transaction.builder()
                .account(account)
                .type(TransactionType.WITHDRAW)
                .amount(amount)
                .balanceAfter(account.getBalance())
                .description(description)
                .idempotencyKey(idempotencyKey)
                .status(TransactionStatus.SUCCESS)
                .build();
    }

    // Enum
    public enum TransactionType {
        DEPOSIT, WITHDRAW, TRANSFER_IN, TRANSFER_OUT
    }

    public enum TransactionStatus {
        SUCCESS, FAILED, PENDING
    }
}
