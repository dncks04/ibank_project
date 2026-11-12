package com.ibank.domain.transaction.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

/** 요청한 ID의 거래가 없다. */
public class TransactionNotFoundException extends BusinessException {

    public TransactionNotFoundException(Long transactionId) {
        super(HttpStatus.NOT_FOUND,
                "거래를 찾을 수 없습니다.",
                "거래를 찾을 수 없음: transactionId=" + transactionId);
    }
}
