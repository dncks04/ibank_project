package com.ibank.domain.account.entity;

import com.ibank.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String accountNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User owner;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    // 개설 멱등성 키: 같은 키 재요청은 기존 계좌를 replay한다 (중복 생성 방지). UNIQUE.
    @Column(length = 64, unique = true)
    private String openIdempotencyKey;

    // 해지 멱등성 키: 같은 키로 이미 해지된 계좌면 재요청을 성공으로 흡수한다 (중복 해지 방지).
    @Column(length = 64)
    private String closeIdempotencyKey;

    // 낙관적 락: 동시 수정 충돌 감지
    @Version
    private Long version;

    @Builder
    public Account(String accountNumber, User owner, BigDecimal initialBalance, String openIdempotencyKey) {
        this.accountNumber = accountNumber;
        this.owner = owner;
        this.balance = initialBalance;
        this.status = AccountStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
        this.openIdempotencyKey = openIdempotencyKey;
    }

    public void deposit(BigDecimal amount) {
        validateActive();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("입금액은 0보다 커야 합니다.");
        }
        this.balance = this.balance.add(amount);
    }

    public void withdraw(BigDecimal amount) {
        validateActive();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("출금액은 0보다 커야 합니다.");
        }
        if (this.balance.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(this.accountNumber, this.balance, amount);
        }
        this.balance = this.balance.subtract(amount);
    }

    public void close(String idempotencyKey) {
        validateActive();
        this.status = AccountStatus.CLOSED;
        this.closeIdempotencyKey = idempotencyKey;
    }

    private void validateActive() {
        if (this.status != AccountStatus.ACTIVE) {
            throw new com.ibank.domain.account.exception.InactiveAccountException();
        }
    }

    public enum AccountStatus {
        ACTIVE, SUSPENDED, CLOSED
    }
}
