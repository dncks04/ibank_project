package com.ibank.domain.fraud.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 이미 처리된(RESOLVED) 경보를 다시 검토하려 할 때.
 *
 * <p>두 운영자가 같은 경보를 동시에 승인/반려하는 경우, 비관적 락으로 직렬화된 뒤
 * 나중에 락을 얻은 쪽이 이 예외로 즉시 중단된다(자금 이동 전). 409로 매핑된다.
 */
public class FraudAlertAlreadyResolvedException extends BusinessException {

    public FraudAlertAlreadyResolvedException(Long alertId) {
        super(HttpStatus.CONFLICT,
                "이미 처리된 경보입니다.",
                "이미 처리된 이상거래 경보 재검토 시도: id=" + alertId);
    }
}
