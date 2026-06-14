package com.ibank.domain.fraud.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

/** 검토 대상 경보를 찾을 수 없을 때. 어떤 id인지 클라이언트에 노출하지 않는다. */
public class FraudAlertNotFoundException extends BusinessException {

    public FraudAlertNotFoundException(Long alertId) {
        super(HttpStatus.NOT_FOUND,
                "경보를 찾을 수 없습니다.",
                "이상거래 경보 없음: id=" + alertId);
    }
}
