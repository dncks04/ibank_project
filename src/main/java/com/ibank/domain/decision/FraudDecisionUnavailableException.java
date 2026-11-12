package com.ibank.domain.decision;

/**
 * FDS 서비스에서 판정을 받지 못했다. 타임아웃, 연결 실패, 2xx가 아닌 응답, 해석할 수 없는 응답,
 * 서킷 브레이커가 열린 상태가 모두 여기에 해당한다. 거래 실패가 아니라 "대체 정책으로 판정하라"는 신호다.
 */
public class FraudDecisionUnavailableException extends RuntimeException {

    public FraudDecisionUnavailableException(String message) {
        super(message);
    }

    public FraudDecisionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
