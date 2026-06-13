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
 *
 * <p>복식부기: 같은 {@code journalId}를 공유하는 leg들이 하나의 분개(journal)를 이루며,
 * 모든 분개는 SUM(CREDIT) - SUM(DEBIT) == 0 이어야 한다. 고객 계좌 간 이체는 고객 leg 2건,
 * 입금/출금/개설 초기 잔액처럼 자금이 외부 경계를 넘는 거래는 고객 leg + {@link SystemAccount} leg로 기록한다.
 *
 * <p>각 leg는 고객 계좌({@code account}) 또는 시스템 계정({@code systemAccount}) 중
 * 정확히 하나에 귀속된다. 시스템 leg는 잔액 스냅샷이 없으므로 {@code balanceAfter}가 null이다.
 */
@Entity
@Table(name = "ledger_entries", indexes = {
        @Index(name = "idx_ledger_entries_account_id", columnList = "account_id"),
        @Index(name = "idx_ledger_entries_transaction_id", columnList = "transaction_id"),
        @Index(name = "idx_ledger_entries_journal_id", columnList = "journal_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 하나의 균형 분개를 이루는 leg들이 공유하는 식별자
    @Column(name = "journal_id", nullable = false, length = 64)
    private String journalId;

    // 계좌 개설 초기 잔액 항목은 거래가 없으므로 nullable
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;

    // 고객 계좌 leg일 때만 non-null (시스템 leg는 systemAccount에 귀속)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private Account account;

    // 시스템 계정 leg일 때만 non-null
    @Enumerated(EnumType.STRING)
    @Column(name = "system_account", length = 20)
    private SystemAccount systemAccount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private LedgerDirection direction;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // 고객 leg: 이 항목 반영 후 계좌 잔액. 시스템 leg: null (잔액 행을 유지하지 않음)
    @Column(precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LedgerEntry(String journalId, Transaction transaction, Account account,
                        SystemAccount systemAccount, LedgerDirection direction,
                        BigDecimal amount, BigDecimal balanceAfter) {
        if ((account == null) == (systemAccount == null)) {
            throw new IllegalArgumentException("원장 leg는 고객 계좌 또는 시스템 계정 중 정확히 하나에 귀속되어야 합니다.");
        }
        this.journalId = journalId;
        this.transaction = transaction;
        this.account = account;
        this.systemAccount = systemAccount;
        this.direction = direction;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.createdAt = LocalDateTime.now();
    }

    /** 거래에 의한 고객 계좌 leg (잔액 변동 후의 balanceAfter 기록). */
    public static LedgerEntry of(String journalId, Transaction transaction, Account account,
                                 LedgerDirection direction, BigDecimal amount) {
        return new LedgerEntry(journalId, transaction, account, null, direction, amount, account.getBalance());
    }

    /** 시스템 계정 leg. 외부 경계 거래(입금/출금/개설)의 상대 leg. */
    public static LedgerEntry system(String journalId, Transaction transaction, SystemAccount systemAccount,
                                     LedgerDirection direction, BigDecimal amount) {
        return new LedgerEntry(journalId, transaction, null, systemAccount, direction, amount, null);
    }

    /** 계좌 개설 시 초기 잔액 고객 leg (거래 없음, CREDIT). */
    public static LedgerEntry opening(String journalId, Account account, BigDecimal amount) {
        return new LedgerEntry(journalId, null, account, null, LedgerDirection.CREDIT, amount, amount);
    }

    /** 이자 지급 고객 leg (거래 없음, CREDIT). 입금 반영 후 잔액을 스냅샷한다. */
    public static LedgerEntry interestCredit(String journalId, Account account, BigDecimal amount) {
        return new LedgerEntry(journalId, null, account, null, LedgerDirection.CREDIT, amount, account.getBalance());
    }
}
