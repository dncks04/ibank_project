package com.ibank.domain.account.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 비활성(해지/정지) 계좌에 대한 거래 시도. 클라이언트에는 계좌 식별자를 노출하지 않는다.
 */
public class InactiveAccountException extends BusinessException {
    public InactiveAccountException() {
        super(HttpStatus.CONFLICT, "비활성 상태인 계좌입니다.");
    }
}
