package com.ibank.domain.transaction.service;

import com.ibank.domain.decision.FraudDecisionPort.Decision;
import com.ibank.domain.decision.FraudDecisionPort.EvaluationRequest;
import com.ibank.domain.decision.FraudDecisionService;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransactionProcessor.Outcome;
import com.ibank.global.config.FdsProperties;
import com.ibank.global.metrics.IbankMetrics;
import com.ibank.global.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 판정 도중 처리가 끊겨 PENDING_REVIEW로 남은 거래의 복구.
 *
 * <p>2단계 처리는 가승인(1단계)을 먼저 커밋한 뒤 판정과 확정을 진행한다. 그 사이에 프로세스가 죽거나
 * 확정 단계가 재시도 끝에 실패하면, 거래는 PENDING_REVIEW, 홀드는 ACTIVE로 남는다. 돈은 움직이지 않았지만
 * 금액이 묶여 있으므로 누군가 마저 처리해야 한다.
 *
 * <p>복구는 <b>판정부터 다시</b> 한다. 정상 흐름의 2·3단계를 그대로 재실행하는 것이라 별도 규칙이 없다.
 * FDS 서비스가 여전히 응답하지 않으면 대체 정책이 판정한다. 확정 단계({@code applyDecision})는 거래가
 * 아직 PENDING_REVIEW일 때만 상태를 바꾸므로, 원래 요청 스레드가 뒤늦게 끝나거나 다른 인스턴스의 복구와
 * 겹쳐도 한 번만 적용된다.
 *
 * <p>대상은 가승인 뒤 {@code ibank.fds.recovery.stale-after}(기본 1분)가 지난 거래뿐이다. 이 시간은 정상 처리
 * 시간(판정 타임아웃 + 확정 재시도)보다 충분히 길어야, 지금 처리 중인 거래를 복구가 가로채지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PendingReviewRecoveryService {

    private final TransactionRepository transactionRepository;
    private final TransactionProcessor processor;
    private final FraudDecisionService fraudDecisionService;
    private final FdsProperties properties;
    private final BusinessClock businessClock;
    private final IbankMetrics metrics;

    /**
     * 오래된 PENDING_REVIEW 거래를 재판정해 확정한다. 트랜잭션 없이 호출해야 한다(판정은 락 밖에서).
     *
     * @return 이번 실행에서 PENDING_REVIEW를 벗어난 거래 수
     */
    public int recoverStale() {
        LocalDateTime cutoff = businessClock.nowForStorage().minus(properties.recovery().staleAfter());
        List<Long> staleIds = transactionRepository.findStalePendingReviewIds(
                cutoff, PageRequest.of(0, properties.recovery().batchSize()));

        int recovered = 0;
        for (Long transactionId : staleIds) {
            try {
                if (recover(transactionId)) {
                    recovered++;
                }
            } catch (Exception e) {
                // 한 건의 실패가 나머지 복구를 막지 않는다. 이 거래는 다음 실행에서 다시 시도된다.
                log.error("판정 대기 거래 복구 실패 (transactionId={}) — 다음 실행에서 재시도", transactionId, e);
                metrics.countOperation("PENDING_REVIEW_RECOVERY", "FAILURE");
            }
        }
        if (!staleIds.isEmpty()) {
            log.info("판정 대기 거래 복구: 대상 {}건 중 {}건 확정", staleIds.size(), recovered);
        }
        return recovered;
    }

    private boolean recover(Long transactionId) {
        Optional<EvaluationRequest> request = processor.evaluationRequestOf(transactionId);
        if (request.isEmpty()) {
            return false; // 조회와 처리 사이에 다른 경로가 확정함
        }
        Decision decision = fraudDecisionService.decide(request.get());
        Outcome outcome = processor.applyDecision(transactionId, decision);
        metrics.countOperation("PENDING_REVIEW_RECOVERY", "SUCCESS");
        log.info("판정 대기 거래 복구 (transactionId={}): {} → {}",
                transactionId, decision.verdict(), outcome.response().status());
        return true;
    }
}
