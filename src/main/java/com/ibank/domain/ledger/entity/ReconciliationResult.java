package com.ibank.domain.ledger.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 정산 배치 1회 실행에서 한 계좌의 검증 결과 스냅샷.
 * consistent == false 이면 잔액과 원장 합계가 불일치(조사 대상).
 */
@Entity
@Table(name = "reconciliation_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long jobExecutionId;

    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal ledgerSum;

    @Column(nullable = false)
    private boolean consistent;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private ReconciliationResult(Long jobExecutionId, Long accountId,
                                 BigDecimal balance, BigDecimal ledgerSum, boolean consistent) {
        this.jobExecutionId = jobExecutionId;
        this.accountId = accountId;
        this.balance = balance;
        this.ledgerSum = ledgerSum;
        this.consistent = consistent;
        this.createdAt = LocalDateTime.now();
    }

    public static ReconciliationResult of(Long jobExecutionId, Long accountId,
                                          BigDecimal balance, BigDecimal ledgerSum) {
        boolean consistent = balance.compareTo(ledgerSum) == 0;
        return new ReconciliationResult(jobExecutionId, accountId, balance, ledgerSum, consistent);
    }
}
