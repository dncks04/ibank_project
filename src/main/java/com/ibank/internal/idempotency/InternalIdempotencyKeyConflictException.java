package com.ibank.internal.idempotency;

/**
 * 같은 Idempotency-Key가 다른 요청(다른 동작·대상·본문)에 이미 쓰였다.
 * 저장된 응답을 돌려주면 호출 서비스는 엉뚱한 요청의 결과를 자기 요청의 결과로 받게 되므로 거부한다.
 */
public class InternalIdempotencyKeyConflictException extends RuntimeException {

    public InternalIdempotencyKeyConflictException(String operation) {
        super("Idempotency-Key가 다른 요청에 이미 사용됨 (이번 요청: " + operation + ")");
    }
}
