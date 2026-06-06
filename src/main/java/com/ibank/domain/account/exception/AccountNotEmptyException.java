package com.ibank.domain.account.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 잔액이 남아있는 계좌의 해지 시도.
 */
public class AccountNotEmptyException extends BusinessException {
    public AccountNotEmptyException() {
        super(HttpStatus.CONFLICT, "잔액이 남아있는 계좌는 해지할 수 없습니다.");
    }
}
