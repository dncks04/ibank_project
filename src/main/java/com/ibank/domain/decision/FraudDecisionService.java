package com.ibank.domain.decision;

import com.ibank.domain.decision.FraudDecisionPort.Decision;
import com.ibank.domain.decision.FraudDecisionPort.EvaluationRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래 판정의 진입점. 판정 포트를 호출해 승인·보류를 받는다.
 *
 * <p>{@code Propagation.NEVER}: 트랜잭션 안에서 호출하면 예외를 던진다. 포트 구현체가 외부 HTTP를 호출할 수
 * 있으므로, DB 행 락이나 커넥션을 쥔 채 판정을 기다리는 코드를 실수로라도 만들 수 없게 한다.
 */
@Service
@RequiredArgsConstructor
public class FraudDecisionService {

    private final FraudDecisionPort port;

    @Transactional(propagation = Propagation.NEVER)
    public Decision decide(EvaluationRequest request) {
        return port.evaluate(request);
    }
}
