package com.ibank.domain.ledger.entity;

/**
 * 내부 시스템 계정(GL 계정). 고객 계좌가 아닌 원장 leg의 귀속처.
 *
 * <p>입금/출금/계좌 개설처럼 자금이 시스템 외부 경계를 넘는 거래는 고객 계좌 leg만으로는
 * 복식부기가 성립하지 않으므로(분개 합 != 0), 상대 leg를 시스템 계정에 기록한다.
 * 이로써 모든 분개(journal)의 SUM(CREDIT) - SUM(DEBIT) == 0 불변식이 성립하고,
 * 시스템 전체에서 돈이 생기거나 사라지는 버그를 시산표(trial balance)로 탐지할 수 있다.
 *
 * <p>시스템 계정은 잔액 행(row)을 갱신하지 않고 원장 leg만 insert한다(insert-only).
 * 모든 입출금이 한 행을 잠그는 핫스팟 락 경합이 구조적으로 발생하지 않으며,
 * 잔액이 필요하면 원장 합산으로 도출한다.
 */
public enum SystemAccount {

    /** 외부 자금 경계(입금·출금·개설 초기 잔액)의 상대 계정. */
    CLEARING
}
