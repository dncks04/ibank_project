package com.ibank.domain.decision.adapter;

import com.ibank.domain.decision.FraudDecisionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 모든 거래를 승인하는 판정 어댑터. FDS 서비스 없이 ibank만 띄우는 로컬 개발·테스트용이다.
 *
 * <p>{@code ibank.fds.mode=always-approve}(기본값)일 때 쓰인다. 운영 프로파일은 {@code http}로 바꿔
 * 실제 FDS 서비스를 호출한다. 이 모드에서 판정 주체는 {@code LOCAL}로 기록되어, FDS 서비스가 판정한 거래와 구분된다.
 */
@Component
@ConditionalOnProperty(name = "ibank.fds.mode", havingValue = "always-approve", matchIfMissing = true)
public class AlwaysApproveFraudDecisionAdapter implements FraudDecisionPort {

    @Override
    public Decision evaluate(EvaluationRequest request) {
        return Decision.approve(Source.LOCAL);
    }
}
