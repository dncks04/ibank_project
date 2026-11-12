package com.ibank.domain.decision;

import com.ibank.domain.transaction.entity.Transaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 거래를 실행할지 보류할지 묻는 포트. 판단 책임은 FDS·보호 흐름 서비스에 있고, ibank는 결과만 받는다.
 *
 * <p><b>호출 규약</b>: 구현체가 외부 HTTP를 호출할 수 있으므로, 호출자는 DB 행 락과 트랜잭션을
 * 쥐지 않은 상태에서 호출해야 한다. 계약은 {@code docs/contracts/fds-evaluate.openapi.yaml}.
 *
 * <p>판정은 승인(APPROVE)과 보류(HOLD) 두 가지뿐이다. 거절은 없다 — 취소는 FDS 서비스가
 * 보호자·고객 확인 뒤 내부 API로 따로 요청한다.
 */
public interface FraudDecisionPort {

    /**
     * @throws FraudDecisionUnavailableException 판정을 받지 못한 경우(타임아웃, 연결 실패, 잘못된 응답, 서킷 열림).
     *                                           호출자는 대체 정책으로 판정한다.
     */
    Decision evaluate(EvaluationRequest request);

    /** 판정 요청. FDS 서비스와의 계약 필드 그대로다. */
    record EvaluationRequest(
            Long transactionId,
            String idempotencyKey,
            Transaction.TransactionType type,
            Transaction.Channel channel,
            String fromAccountNumber,
            String toAccountNumber,
            BigDecimal amount,
            Long customerId,
            OffsetDateTime requestedAt
    ) {}

    /**
     * 판정 결과.
     *
     * @param decisionId  FDS 서비스가 발급한 판정 식별자. 대체 정책·로컬 판정이면 null.
     * @param riskLevel   위험 등급. 알 수 없으면 null.
     * @param reasonCodes 사유 코드. 없으면 빈 목록.
     * @param source      누가 판정했는지.
     */
    record Decision(
            Verdict verdict,
            String decisionId,
            RiskLevel riskLevel,
            List<String> reasonCodes,
            Source source
    ) {
        public Decision {
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }

        public static Decision approve(Source source) {
            return new Decision(Verdict.APPROVE, null, null, List.of(), source);
        }

        public static Decision hold(Source source, List<String> reasonCodes) {
            return new Decision(Verdict.HOLD, null, null, reasonCodes, source);
        }

        public boolean isHold() {
            return verdict == Verdict.HOLD;
        }
    }

    enum Verdict {
        APPROVE, HOLD
    }

    enum RiskLevel {
        NORMAL, CAUTION, DANGER, HIGH_RISK
    }

    /** 판정 주체. */
    enum Source {
        /** FDS 서비스가 판정함. */
        FDS,
        /** FDS 서비스에서 판정을 받지 못해 ibank의 대체 정책이 판정함. */
        FALLBACK,
        /** FDS 서비스를 연동하지 않은 모드(개발·테스트)에서 ibank 안에서 판정함. */
        LOCAL
    }
}
