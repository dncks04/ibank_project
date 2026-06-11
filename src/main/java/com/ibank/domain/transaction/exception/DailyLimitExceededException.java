package com.ibank.domain.transaction.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/**
 * 1일 누적 출금(이체+출금) 한도 초과.
 * 클라이언트에는 한도만 안내하고, 계좌번호·사용액은 로그에만 남긴다.
 */
public class DailyLimitExceededException extends BusinessException {
    public DailyLimitExceededException(String accountNumber, BigDecimal used,
                                       BigDecimal requested, BigDecimal limit) {
        super(HttpStatus.BAD_REQUEST,
                "1일 출금·이체 한도(" + limit.toPlainString() + "원)를 초과했습니다.",
                "1일 한도 초과: account=" + accountNumber + ", used=" + used.toPlainString()
                        + ", requested=" + requested.toPlainString() + ", limit=" + limit.toPlainString());
    }
}
