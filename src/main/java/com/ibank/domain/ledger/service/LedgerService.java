package com.ibank.domain.ledger.service;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.ledger.entity.LedgerDirection;
import com.ibank.domain.ledger.entity.LedgerEntry;
import com.ibank.domain.ledger.entity.SystemAccount;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.transaction.entity.Transaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 원장 기록. 반드시 잔액을 변경하는 비즈니스 트랜잭션 내부에서 호출되어,
 * 잔액 변경과 원장 기록이 하나의 트랜잭션으로 커밋/롤백되도록 한다.
 *
 * <p>복식부기: 모든 공개 메서드는 합이 0인 분개(journal) 단위로만 기록한다.
 * 호출자가 leg를 개별 조립할 수 없으므로 불균형 분개가 생성될 수 없다.
 * 입금/출금/개설처럼 자금이 외부 경계를 넘는 거래는 {@link SystemAccount#CLEARING}이 상대 leg를 진다.
 */
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    /** 이체: 출금 계좌 DEBIT + 입금 계좌 CREDIT (고객 leg 2건, 합 0). */
    public void recordTransfer(Transaction transaction, Account from, Account to, BigDecimal amount) {
        String journalId = newJournalId();
        ledgerEntryRepository.save(LedgerEntry.of(journalId, transaction, from, LedgerDirection.DEBIT, amount));
        ledgerEntryRepository.save(LedgerEntry.of(journalId, transaction, to, LedgerDirection.CREDIT, amount));
    }

    /** 입금: 고객 계좌 CREDIT + 클리어링 DEBIT (외부에서 들어온 자금의 상대 leg). */
    public void recordDeposit(Transaction transaction, Account account, BigDecimal amount) {
        String journalId = newJournalId();
        ledgerEntryRepository.save(LedgerEntry.of(journalId, transaction, account, LedgerDirection.CREDIT, amount));
        ledgerEntryRepository.save(LedgerEntry.system(journalId, transaction, SystemAccount.CLEARING,
                LedgerDirection.DEBIT, amount));
    }

    /** 출금: 고객 계좌 DEBIT + 클리어링 CREDIT (외부로 나간 자금의 상대 leg). */
    public void recordWithdrawal(Transaction transaction, Account account, BigDecimal amount) {
        String journalId = newJournalId();
        ledgerEntryRepository.save(LedgerEntry.of(journalId, transaction, account, LedgerDirection.DEBIT, amount));
        ledgerEntryRepository.save(LedgerEntry.system(journalId, transaction, SystemAccount.CLEARING,
                LedgerDirection.CREDIT, amount));
    }

    /**
     * 이자 지급: 고객 계좌 CREDIT + INTEREST_EXPENSE 시스템 계정 DEBIT (합 0인 분개).
     * 입금/출금과 달리 거래(Transaction) 없이 배치가 직접 적립분을 지급하므로 transaction leg는 null이다.
     * 반환한 journalId로 지급된 적립 항목을 표시해 멱등성을 확보한다.
     */
    public String recordInterestPayment(Account account, BigDecimal amount) {
        String journalId = newJournalId();
        ledgerEntryRepository.save(LedgerEntry.interestCredit(journalId, account, amount));
        ledgerEntryRepository.save(LedgerEntry.system(journalId, null, SystemAccount.INTEREST_EXPENSE,
                LedgerDirection.DEBIT, amount));
        return journalId;
    }

    /** 계좌 개설 초기 잔액: 입금과 동일하게 클리어링이 상대 leg. 0 이하이면 기록하지 않는다. */
    public void recordOpening(Account account, BigDecimal initialBalance) {
        if (initialBalance == null || initialBalance.signum() <= 0) {
            return;
        }
        String journalId = newJournalId();
        ledgerEntryRepository.save(LedgerEntry.opening(journalId, account, initialBalance));
        ledgerEntryRepository.save(LedgerEntry.system(journalId, null, SystemAccount.CLEARING,
                LedgerDirection.DEBIT, initialBalance));
    }

    private String newJournalId() {
        return UUID.randomUUID().toString();
    }
}
