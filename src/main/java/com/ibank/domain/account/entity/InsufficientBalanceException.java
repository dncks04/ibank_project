package com.ibank.domain.account.entity;

import java.math.BigDecimal;

public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String accountNumber, BigDecimal balance, BigDecimal requested) {
        super(String.format("잔액 부족 - 계좌: %s, 잔액: %s, 요청: %s", accountNumber, balance, requested));
    }
}
