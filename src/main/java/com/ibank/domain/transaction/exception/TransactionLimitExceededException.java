package com.ibank.domain.transaction.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/** 단건(1회) 거래 한도 초과. 한도는 비밀이 아니므로 클라이언트에 안내한다. */
public class TransactionLimitExceededException extends BusinessException {
    public TransactionLimitExceededException(BigDecimal amount, BigDecimal limit) {
        super(HttpStatus.BAD_REQUEST,
                "1회 거래 한도(" + limit.toPlainString() + "원)를 초과했습니다.",
                "단건 한도 초과: amount=" + amount.toPlainString() + ", limit=" + limit.toPlainString());
    }
}
