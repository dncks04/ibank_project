package com.ibank.domain.transaction.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 동일한 멱등성 키가 서로 다른 내용의 요청에 재사용되었을 때 발생한다.
 *
 * 멱등성은 "같은 요청을 여러 번 보내도 한 번 처리된 것과 같다"는 보장이다. 따라서 같은 키에
 * 다른 금액·계좌가 실리면 그 보장이 깨지므로, 옛 결과를 조용히 반환하지 않고 409로 거부한다.
 */
public class IdempotencyKeyConflictException extends BusinessException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super(HttpStatus.CONFLICT,
                "동일한 멱등성 키로 다른 내용의 요청이 접수되었습니다.",
                "멱등성 키 충돌: key=" + idempotencyKey);
    }
}
