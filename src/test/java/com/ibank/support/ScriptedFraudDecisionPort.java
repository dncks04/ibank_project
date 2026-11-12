package com.ibank.support;

import com.ibank.domain.decision.FraudDecisionPort;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 테스트가 판정을 마음대로 정할 수 있는 판정 포트.
 *
 * <p>컴포넌트로 등록하지 않는다(등록하면 모든 테스트의 판정이 바뀐다). 필요한 테스트 클래스가
 * {@code @TestConfiguration}에서 {@code @Primary} 빈으로 직접 올린다.
 */
public class ScriptedFraudDecisionPort implements FraudDecisionPort {

    private final List<EvaluationRequest> requests = new CopyOnWriteArrayList<>();
    private volatile Function<EvaluationRequest, Decision> behavior;
    private volatile Consumer<EvaluationRequest> duringEvaluation;

    public ScriptedFraudDecisionPort() {
        reset();
    }

    /** 기본 동작(모두 승인)으로 되돌리고 기록을 지운다. */
    public void reset() {
        requests.clear();
        duringEvaluation = null;
        approveAll();
    }

    public void approveAll() {
        behavior = request -> new Decision(Verdict.APPROVE, "decision-" + request.transactionId(),
                RiskLevel.NORMAL, List.of(), Source.FDS);
    }

    public void holdAll(String... reasonCodes) {
        behavior = request -> new Decision(Verdict.HOLD, "decision-" + request.transactionId(),
                RiskLevel.DANGER, List.of(reasonCodes), Source.FDS);
    }

    /** 요청마다 판정을 계산한다. */
    public void decideBy(Function<EvaluationRequest, Decision> behavior) {
        this.behavior = behavior;
    }

    /** 판정 호출이 예외로 끝나게 한다(판정 도중 프로세스가 죽거나 판정 서비스가 응답하지 않는 상황). */
    public void failWith(RuntimeException exception) {
        behavior = request -> {
            throw exception;
        };
    }

    /** 판정이 진행되는 동안(= 1단계 커밋 뒤, 3단계 시작 전) 실행할 동작. 그 시점의 상태를 검증하거나 바꾼다. */
    public void duringEvaluation(Consumer<EvaluationRequest> action) {
        this.duringEvaluation = action;
    }

    public List<EvaluationRequest> requests() {
        return requests;
    }

    @Override
    public Decision evaluate(EvaluationRequest request) {
        requests.add(request);
        Consumer<EvaluationRequest> hook = duringEvaluation;
        if (hook != null) {
            hook.accept(request);
        }
        return behavior.apply(request);
    }
}
