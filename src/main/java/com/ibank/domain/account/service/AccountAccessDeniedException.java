package com.ibank.domain.account.service;

public class AccountAccessDeniedException extends RuntimeException {

    public AccountAccessDeniedException(String accountNumber) {
        super("해당 계좌에 접근 권한이 없습니다: " + accountNumber);
    }
}
