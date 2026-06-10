package com.ibank.domain.transaction.entity;

import com.ibank.domain.account.entity.Account;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions", indexes = {
    @Index(name = "idx_transaction_from_account_created", columnList = "from_account_id, created_at DESC"),
    @Index(name = "idx_transaction_to_account_created", columnList = "to_account_id, created_at DESC"),
    @Index(name = "idx_transaction_idempotency_key", columnList = "idempotency_key", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 멱등성 키: 중복 이체 방지
    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    // 요청 지문: 같은 멱등성 키가 다른 내용의 요청에 재사용되었는지 식별 (replay 안전성)
    @Column(length = 64)
    private String requestFingerprint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account_id")
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id")
    private Account toAccount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(length = 200)
    private String description;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public Transaction(Account fromAccount, Account toAccount, BigDecimal amount,
                       TransactionType type, String description) {
        this.idempotencyKey = UUID.randomUUID().toString();
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.amount = amount;
        this.type = type;
        this.status = TransactionStatus.PENDING;
        this.description = description;
        this.createdAt = LocalDateTime.now();
    }

    public Transaction withIdempotencyKey(String key) {
        this.idempotencyKey = key;
        return this;
    }

    /** 멱등성 키와 함께 요청 지문을 기록한다 (재요청 시 동일 요청인지 검증하기 위함). */
    public Transaction withIdempotency(String key, String requestFingerprint) {
        this.idempotencyKey = key;
        this.requestFingerprint = requestFingerprint;
        return this;
    }

    public void complete() {
        if (this.status != TransactionStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태인 거래만 완료할 수 있습니다.");
        }
        this.status = TransactionStatus.COMPLETED;
    }

    public void fail() {
        if (this.status != TransactionStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태인 거래만 실패 처리할 수 있습니다.");
        }
        this.status = TransactionStatus.FAILED;
    }

    public enum TransactionType {
        DEPOSIT, WITHDRAWAL, TRANSFER
    }

    public enum TransactionStatus {
        PENDING, COMPLETED, FAILED
    }
}
