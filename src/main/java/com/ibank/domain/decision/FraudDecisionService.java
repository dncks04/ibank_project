package com.ibank.domain.decision;

import com.ibank.domain.decision.FraudDecisionPort.Decision;
import com.ibank.domain.decision.FraudDecisionPort.EvaluationRequest;
import com.ibank.global.audit.AuditService;
import com.ibank.global.metrics.IbankMetrics;
import com.ibank.global.web.CorrelationIdFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래 판정의 진입점. 판정 포트를 호출해 승인·보류를 받고, 받지 못하면 대체 정책으로 판정한다.
 *
 * <p>{@code Propagation.NEVER}: 트랜잭션 안에서 호출하면 예외를 던진다. 포트 구현체가 외부 HTTP를 호출할 수
 * 있으므로, DB 행 락이나 커넥션을 쥔 채 판정을 기다리는 코드를 실수로라도 만들 수 없게 한다.
 *
 * <p>대체 정책은 {@link FraudDecisionUnavailableException}에만 적용한다. 그 밖의 예외는 판정 서비스의 장애가
 * 아니라 ibank 쪽 문제이므로 삼키지 않고 그대로 올린다(거래는 PENDING_REVIEW로 남고 복구 배치가 재판정한다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FraudDecisionService {

    static final String AUDIT_ACTION_FALLBACK = "FDS_FALLBACK";

    private final FraudDecisionPort port;
    private final FallbackDecisionPolicy fallbackPolicy;
    private final AuditService auditService;
    private final IbankMetrics metrics;

    @Transactional(propagation = Propagation.NEVER)
    public Decision decide(EvaluationRequest request) {
        try {
            return port.evaluate(request);
        } catch (FraudDecisionUnavailableException e) {
            Decision fallback = fallbackPolicy.decide(request);
            log.warn("FDS 판정 불가 → 대체 정책 적용 (transactionId={}, decision={}): {}",
                    request.transactionId(), fallback.verdict(), e.getMessage());
            recordFallback(request, fallback);
            return fallback;
        }
    }

    /** 대체 정책이 판정했다는 사실을 감사 로그와 메트릭에 남긴다. 기록 실패가 거래 처리를 막지는 않는다. */
    private void recordFallback(EvaluationRequest request, Decision fallback) {
        metrics.countOperation(AUDIT_ACTION_FALLBACK, fallback.verdict().name());
        try {
            auditService.record(
                    currentActor(),
                    AUDIT_ACTION_FALLBACK,
                    "transaction:" + request.transactionId(),
                    "SUCCESS",
                    "decisionSource=FALLBACK;decision=" + fallback.verdict(),
                    null,
                    MDC.get(CorrelationIdFilter.MDC_KEY));
        } catch (Exception auditFailure) {
            log.error("대체 정책 감사 기록 실패 (transactionId={}) — 거래 처리는 계속", request.transactionId(), auditFailure);
        }
    }

    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "SYSTEM"; // 복구 배치처럼 요청 맥락 없이 판정하는 경우
        }
        return auth.getName();
    }
}
