package com.ibank.domain.ledger.entity;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.transaction.entity.Transaction;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 불변 원장 항목. 한 번 기록되면 수정/삭제하지 않는다(append-only).
 * 거래의 각 leg(이체는 2건: 출금 DEBIT + 입금 CREDIT)와 계좌 개설 시 초기 잔액을 기록한다.
 */
@Entity
@Table(name = "ledger_entries", indexes = {
        @Index(name = "idx_ledger_entries_account_id", columnList = "account_id"),
        @Index(name = "idx_ledger_entries_transaction_id", columnList = "transaction_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 계좌 개설 초기 잔액 항목은 거래가 없으므로 nullable
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private LedgerDirection direction;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LedgerEntry(Transaction transaction, Account account, LedgerDirection direction,
                        BigDecimal amount, BigDecimal balanceAfter) {
        this.transaction = transaction;
        this.account = account;
        this.direction = direction;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.createdAt = LocalDateTime.now();
    }

    /** 거래에 의한 항목 (잔액 변동 후의 balanceAfter 기록). */
    public static LedgerEntry of(Transaction transaction, Account account,
                                 LedgerDirection direction, BigDecimal amount) {
        return new LedgerEntry(transaction, account, direction, amount, account.getBalance());
    }

    /** 계좌 개설 시 초기 잔액 항목(거래 없음, CREDIT). */
    public static LedgerEntry opening(Account account, BigDecimal amount) {
        return new LedgerEntry(null, account, LedgerDirection.CREDIT, amount, amount);
    }
}
