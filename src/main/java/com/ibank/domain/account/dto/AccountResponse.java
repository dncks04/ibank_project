package com.ibank.domain.account.dto;

import com.ibank.domain.account.entity.Account;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 계좌 응답.
 *
 * @param balance          원장잔액. 원장 분개의 합과 항상 같다.
 * @param availableBalance 가용잔액 = 원장잔액 − 묶인 금액. 출금·이체는 이 금액까지만 가능하다.
 * @param heldAmount       판정 대기·보류 중인 거래로 묶인 금액(ACTIVE 홀드 합계).
 */
public record AccountResponse(
        Long id,
        String accountNumber,
        BigDecimal balance,
        Account.AccountStatus status,
        LocalDateTime createdAt,
        BigDecimal availableBalance,
        BigDecimal heldAmount
) {
    public static AccountResponse from(Account account, BigDecimal heldAmount) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getBalance(),
                account.getStatus(),
                account.getCreatedAt(),
                account.getBalance().subtract(heldAmount),
                heldAmount
        );
    }
}
