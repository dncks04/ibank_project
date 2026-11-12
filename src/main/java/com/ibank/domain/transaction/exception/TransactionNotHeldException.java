package com.ibank.domain.transaction.exception;

import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 보류(HELD) 상태가 아닌 거래를 실행·취소하려 했다. 이미 실행·취소·만료된 거래이거나,
 * 같은 거래에 실행과 취소가 동시에 들어와 다른 쪽이 먼저 처리한 경우다.
 */
public class TransactionNotHeldException extends BusinessException {

    public TransactionNotHeldException(Long transactionId, Transaction.TransactionStatus current) {
        super(HttpStatus.CONFLICT,
                "보류 상태의 거래가 아닙니다.",
                "보류 상태가 아닌 거래: transactionId=%s, status=%s".formatted(transactionId, current));
    }
}
