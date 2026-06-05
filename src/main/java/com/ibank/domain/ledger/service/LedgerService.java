package com.ibank.domain.ledger.service;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.ledger.entity.LedgerDirection;
import com.ibank.domain.ledger.entity.LedgerEntry;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.transaction.entity.Transaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 원장 기록. 반드시 잔액을 변경하는 비즈니스 트랜잭션 내부에서 호출되어,
 * 잔액 변경과 원장 기록이 하나의 트랜잭션으로 커밋/롤백되도록 한다.
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    /** 거래 leg 기록. amount는 양수, 방향은 계좌 관점(CREDIT 증가 / DEBIT 감소). */
    public void record(Transaction transaction, Account account, LedgerDirection direction, BigDecimal amount) {
        ledgerEntryRepository.save(LedgerEntry.of(transaction, account, direction, amount));
    }

    /** 계좌 개설 초기 잔액 기록. 0 이하이면 기록하지 않는다. */
    public void recordOpening(Account account, BigDecimal initialBalance) {
        if (initialBalance == null || initialBalance.signum() <= 0) {
            return;
        }
        ledgerEntryRepository.save(LedgerEntry.opening(account, initialBalance));
    }
}
