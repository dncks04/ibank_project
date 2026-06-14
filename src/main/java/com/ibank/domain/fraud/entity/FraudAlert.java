package com.ibank.domain.fraud.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 이상거래 탐지 경보. 의심 룰에 걸려 보류(HELD)된 이체 1건당 한 행.
 * 운영자는 OPEN 경보를 검토 큐로 처리한다.
 */
@Entity
@Table(name = "fraud_alerts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FraudAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long accountId;

    private Long transactionId;

    @Column(nullable = false, length = 200)
    private String triggeredRules;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(length = 500)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private FraudAlert(Long accountId, Long transactionId, String triggeredRules,
                       BigDecimal amount, String detail) {
        this.accountId = accountId;
        this.transactionId = transactionId;
        this.triggeredRules = triggeredRules;
        this.amount = amount;
        this.detail = detail;
        this.status = Status.OPEN;
        this.createdAt = LocalDateTime.now();
    }

    public static FraudAlert of(Long accountId, Long transactionId, List<String> rules,
                                BigDecimal amount, String detail) {
        return new FraudAlert(accountId, transactionId, String.join(",", rules), amount, detail);
    }

    /** 검토 완료 처리. 이미 처리된(RESOLVED) 경보는 다시 처리할 수 없다(중복 검토 방지). */
    public void resolve() {
        if (this.status != Status.OPEN) {
            throw new IllegalStateException("OPEN 상태인 경보만 처리할 수 있습니다.");
        }
        this.status = Status.RESOLVED;
    }

    public enum Status {
        OPEN, RESOLVED
    }
}
