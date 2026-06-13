package com.ibank.domain.interest.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 일일 이자 적립 항목. 한 계좌의 하루치 이자를 한 행으로 적립한다.
 *
 * <p>(account_id, accrual_date) 유니크 제약으로 같은 날 중복 적립이 불가능하다
 * → 일일 적립 배치를 같은 날 다시 돌려도 멱등하다.
 *
 * <p>적립과 지급은 분리된다. 적립 시점에는 {@code paid=false}로 쌓이고, 월말 지급 배치가
 * 미지급 적립분을 합산해 계좌에 입금하면서 {@code paid=true}와 지급 분개 id를 채운다.
 */
@Entity
@Table(name = "interest_accruals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterestAccrual {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private LocalDate accrualDate;

    /** 적립 계산에 사용한 그날의 잔액 스냅샷(감사·재현용). */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceSnapshot;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal annualRate;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal interestAmount;

    @Column(nullable = false)
    private boolean paid;

    /** 지급 시 기록된 원장 분개 id. 미지급분은 null. */
    @Column(length = 64)
    private String paymentJournalId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private InterestAccrual(Long accountId, LocalDate accrualDate, BigDecimal balanceSnapshot,
                            BigDecimal annualRate, BigDecimal interestAmount) {
        this.accountId = accountId;
        this.accrualDate = accrualDate;
        this.balanceSnapshot = balanceSnapshot;
        this.annualRate = annualRate;
        this.interestAmount = interestAmount;
        this.paid = false;
        this.createdAt = LocalDateTime.now();
    }

    public static InterestAccrual of(Long accountId, LocalDate accrualDate, BigDecimal balanceSnapshot,
                                     BigDecimal annualRate, BigDecimal interestAmount) {
        return new InterestAccrual(accountId, accrualDate, balanceSnapshot, annualRate, interestAmount);
    }

    /** 지급 완료로 표시한다. 멱등성: 지급 트랜잭션 안에서 이 표시와 입금/원장이 함께 커밋된다. */
    public void markPaid(String paymentJournalId) {
        this.paid = true;
        this.paymentJournalId = paymentJournalId;
    }
}
