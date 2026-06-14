package com.ibank.domain.transaction.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 경보에 연결된 거래가 보류(HELD) 상태가 아니거나 존재하지 않을 때.
 * 이미 검토 처리된 경보를 다시 처리하려는 경우 등에 발생한다.
 */
public class HeldTransactionNotFoundException extends BusinessException {

    public HeldTransactionNotFoundException(Long transactionId) {
        super(HttpStatus.CONFLICT,
                "검토할 보류 거래가 없습니다. 이미 처리되었을 수 있습니다.",
                "보류 거래 없음/상태 불일치: transactionId=" + transactionId);
    }
}
