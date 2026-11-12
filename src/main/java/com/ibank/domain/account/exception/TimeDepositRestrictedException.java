package com.ibank.domain.account.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 정기예금 계좌에 직접 입금·출금·이체를 시도했다. 정기예금은 가입과 해지로만 자금이 드나든다.
 */
public class TimeDepositRestrictedException extends BusinessException {

    public TimeDepositRestrictedException(String accountNumber) {
        super(HttpStatus.BAD_REQUEST,
                "정기예금 계좌는 직접 입금·출금·이체할 수 없습니다.",
                "정기예금 계좌 직접 거래 시도: " + accountNumber);
    }
}
