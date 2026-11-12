package com.ibank.domain.outbox;

/**
 * Outbox 이벤트 종류. 계약은 {@code docs/contracts/ibank-internal.openapi.yaml}의 Event.
 * 소비자는 모르는 종류를 건너뛰도록 약속돼 있으므로, 종류를 추가하는 것은 호환되는 변경이다.
 */
public enum EventType {
    /** 이체·출금이 가승인됐다(PENDING_REVIEW, 금액 묶임). */
    TRANSACTION_REQUESTED,
    /** 자금 이동과 분개가 끝났다. */
    TRANSACTION_COMPLETED,
    /** 판정이 보류였다. 금액이 묶인 채 실행·취소를 기다린다. */
    TRANSACTION_HELD,
    /** 보류 거래가 취소됐다. */
    TRANSACTION_CANCELLED,
    /** 보류 거래가 최대 보류 시간을 넘겨 해제됐다. */
    TRANSACTION_EXPIRED,
    /** 승인 판정 뒤 실행 시점 재검증에 실패했다. */
    TRANSACTION_FAILED,
    /** 입금이 완료됐다. */
    DEPOSIT,
    ACCOUNT_OPENED,
    ACCOUNT_CLOSED,
    /** 정기예금이 중도해지됐다. */
    TIME_DEPOSIT_TERMINATED,
    ACCOUNT_FROZEN,
    ACCOUNT_UNFROZEN
}
