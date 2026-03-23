package com.safepay.domain.account.entity;

import com.safepay.domain.member.entity.Member;
import com.safepay.global.exception.CustomException;
import com.safepay.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "account_number", nullable = false)
    private String accountNumber;

    @Column(name = "account_number_hash", nullable = false, unique = true, length = 64)
    private String accountNumberHash;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Version
    private Long version;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public Account(Member member, String accountNumber, String accountNumberHash,
                   AccountType accountType) {
        this.member = member;
        this.accountNumber = accountNumber;
        this.accountNumberHash = accountNumberHash;
        this.balance = BigDecimal.ZERO;
        this.accountType = accountType;
        this.status = AccountStatus.ACTIVE;
    }

    // 도메인 로직

    // 입금
    public void deposit(BigDecimal amount) {
        validateActive();
        validateAmount(amount);
        this.balance = this.balance.add(amount);
    }

    // 출금
    public void withdraw(BigDecimal amount) {
        validateActive();
        validateAmount(amount);
        if (this.balance.compareTo(amount) < 0) {
            throw new CustomException(ErrorCode.INSUFFICIENT_BALANCE,
                    String.format("잔액: %s, 출금 요청: %s", this.balance, amount));
        }
        this.balance = this.balance.subtract(amount);
    }

    // 계좌 소유자 확인
    public boolean isOwnedBy(Long memberId) {
        return this.member.getId().equals(memberId);
    }

    // 검증 메서드
    private void validateActive() {
        if (this.status != AccountStatus.ACTIVE) {
            throw new CustomException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CustomException(ErrorCode.INVALID_AMOUNT);
        }
    }

    // Enum
    public enum AccountType {
        CHECKING, SAVINGS
    }

    public enum AccountStatus {
        ACTIVE, FROZEN, CLOSED
    }
}
