package com.ibank.domain.transaction.entity;

import com.ibank.domain.decision.FraudDecisionPort;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 거래에 연결된 판정 결과. 누가(FDS 서비스 / 대체 정책) 어떤 근거로 승인·보류했는지 남긴다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionDecision {

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", length = 10)
    private FraudDecisionPort.Verdict verdict;

    // FDS 서비스가 발급한 판정 식별자. 대체 정책·로컬 판정이면 null.
    @Column(name = "decision_id", length = 64)
    private String decisionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", length = 20)
    private FraudDecisionPort.RiskLevel riskLevel;

    // 사유 코드 목록, 쉼표 구분. 코드는 [A-Z0-9_]만 허용하므로 쉼표와 충돌하지 않는다.
    @Column(name = "reason_codes", length = 1100)
    private String reasonCodes;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_source", length = 10)
    private FraudDecisionPort.Source source;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    public static TransactionDecision from(FraudDecisionPort.Decision decision, LocalDateTime decidedAt) {
        TransactionDecision d = new TransactionDecision();
        d.verdict = decision.verdict();
        d.decisionId = decision.decisionId();
        d.riskLevel = decision.riskLevel();
        d.reasonCodes = decision.reasonCodes().isEmpty() ? null : String.join(",", decision.reasonCodes());
        d.source = decision.source();
        d.decidedAt = decidedAt;
        return d;
    }

    public List<String> reasonCodeList() {
        if (reasonCodes == null || reasonCodes.isBlank()) {
            return List.of();
        }
        return Arrays.asList(reasonCodes.split(","));
    }
}
