package com.ibank.domain.ledger.entity;

/**
 * 원장 항목 방향. CREDIT(입금/증가), DEBIT(출금/감소).
 * 계좌 잔액 = SUM(CREDIT) - SUM(DEBIT).
 */
public enum LedgerDirection {
    CREDIT,
    DEBIT
}
