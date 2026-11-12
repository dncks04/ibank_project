package com.ibank.domain.transaction.entity;

/**
 * 허용되지 않은 거래 상태 전이. 예: 이미 완료된 거래를 다시 취소하려 함.
 *
 * <p>{@link IllegalStateException}의 하위 타입이다 — 사용자 입력 문제가 아니라 호출 코드가 상태를
 * 먼저 확인하지 않은 것이므로, 전역 예외 처리기는 기존 IllegalStateException과 같이 일반화된 409로 응답한다.
 */
public class IllegalTransactionTransitionException extends IllegalStateException {

    public IllegalTransactionTransitionException(Long transactionId,
                                                 Transaction.TransactionStatus from,
                                                 Transaction.TransactionStatus to) {
        super("허용되지 않은 거래 상태 전이: 거래 %s, %s → %s".formatted(transactionId, from, to));
    }
}
