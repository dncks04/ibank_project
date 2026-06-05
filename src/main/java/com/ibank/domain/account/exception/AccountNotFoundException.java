package com.ibank.domain.account.exception;

import com.ibank.global.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 계좌 조회 실패. 클라이언트에는 계좌번호를 노출하지 않고, 로그에만 남긴다.
 */
public class AccountNotFoundException extends BusinessException {
    public AccountNotFoundException(String accountNumber) {
        super(HttpStatus.BAD_REQUEST, "계좌를 찾을 수 없습니다.",
                "계좌를 찾을 수 없습니다: " + accountNumber);
    }
}
