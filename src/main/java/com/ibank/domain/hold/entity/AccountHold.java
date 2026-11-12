package com.ibank.domain.hold.entity;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.transaction.entity.Transaction;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 홀드: 판정 대기·보류 중인 거래의 금액을 출금 계좌에 묶어 둔 것.
 *
 * <p>원장 분개를 만들지 않는다. 원장잔액은 그대로이고 가용잔액(원장잔액 − ACTIVE 홀드 합계)만 줄어든다.
 * 상태는 ACTIVE에서 시작해 CONSUMED / RELEASED / EXPIRED 중 하나로 한 번만 바뀐다(종료 상태에서 되돌릴 수 없다).
 */
@Entity
@Table(name = "account_holds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false, unique = true)
    private Transaction transaction;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private AccountHold(Account account, Transaction transaction, BigDecimal amount,
                        LocalDateTime now, LocalDateTime expiresAt) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("홀드 금액은 0보다 커야 합니다.");
        }
        this.account = account;
        this.transaction = transaction;
        this.amount = amount;
        this.status = Status.ACTIVE;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static AccountHold active(Account account, Transaction transaction, BigDecimal amount,
                                     LocalDateTime now, LocalDateTime expiresAt) {
        return new AccountHold(account, transaction, amount, now, expiresAt);
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    /** 거래가 실행되어 실제 출금으로 전환된다. */
    public void consume(LocalDateTime now) {
        close(Status.CONSUMED, now);
    }

    /** 거래가 취소·실패하여 묶인 금액을 푼다. */
    public void release(LocalDateTime now) {
        close(Status.RELEASED, now);
    }

    /** 최대 보류 시간이 지나 묶인 금액을 푼다. */
    public void expire(LocalDateTime now) {
        close(Status.EXPIRED, now);
    }

    private void close(Status target, LocalDateTime now) {
        if (this.status != Status.ACTIVE) {
            throw new IllegalStateException(
                    "ACTIVE 홀드만 %s로 바꿀 수 있습니다. (현재 %s)".formatted(target, this.status));
        }
        this.status = target;
        this.updatedAt = now;
    }

    public enum Status {
        ACTIVE, CONSUMED, RELEASED, EXPIRED
    }
}
