package com.ibank.domain.ledger.dto;

import java.math.BigDecimal;

/**
 * 한 계좌의 잔액(balance)과 원장 합계(ledgerSum)를 <b>단일 쿼리(= 단일 스냅샷)</b>로 함께 읽어온
 * 정합성 검증용 투영(projection).
 *
 * <p>두 값을 하나의 SQL문에서 읽으므로, 읽기 사이에 이체가 커밋되어 발생하는
 * 읽기 시점 불일치(read skew)로 인한 false positive(오탐)가 구조적으로 발생하지 않는다.
 * 격리수준과 무관하게 "같은 계좌의 balance와 ledgerSum이 같은 시점"임이 보장된다.
 */
public interface AccountLedgerBalance {
    Long getAccountId();
    BigDecimal getBalance();
    BigDecimal getLedgerSum();

    /** 불변식 balance == SUM(CREDIT) - SUM(DEBIT) 성립 여부. */
    default boolean isConsistent() {
        return getBalance().compareTo(getLedgerSum()) == 0;
    }
}
