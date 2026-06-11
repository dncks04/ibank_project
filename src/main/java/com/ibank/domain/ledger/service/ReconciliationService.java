package com.ibank.domain.ledger.service;

import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.ledger.dto.AccountLedgerBalance;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 정합성 검증(reconciliation). 두 층위로 검사한다.
 *
 * <ol>
 *   <li><b>계좌별</b>: account.balance == SUM(CREDIT) - SUM(DEBIT) — 잔액 스냅샷과 원장의 동기화 검증.</li>
 *   <li><b>시스템 전체(복식부기)</b>: 모든 분개(journal)의 합이 0인지, 그리고 전 원장 시산표가 0인지 —
 *       잔액과 원장이 함께 잘못되어 계좌별 검사를 통과하는 버그(돈 창조/소멸)를 탐지.</li>
 * </ol>
 *
 * 배치/스케줄러에서 주기적으로 호출하여 데이터 무결성을 감시할 수 있다.
 *
 * <p>잔액과 원장 합계는 반드시 <b>단일 쿼리(단일 스냅샷)</b>로 함께 읽는다. 둘을 나눠 읽으면
 * 그 사이 이체가 커밋될 때 읽기 시점 불일치(read skew)로 정합 데이터를 불일치로 오탐할 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    /** 단일 계좌 정합성. */
    @Transactional(readOnly = true)
    public boolean isConsistent(Long accountId) {
        return accountRepository.findAccountLedgerBalance(accountId)
                .orElseThrow()
                .isConsistent();
    }

    /** 전 계좌 검증 후 불일치 계좌 id 목록 반환. 빈 목록이면 모두 정합. */
    @Transactional(readOnly = true)
    public List<Long> findInconsistentAccounts() {
        List<Long> inconsistent = new ArrayList<>();
        for (AccountLedgerBalance row : accountRepository.findAllAccountLedgerBalances()) {
            if (!row.isConsistent()) {
                inconsistent.add(row.getAccountId());
                log.warn("정합성 불일치: accountId={}, balance={}, ledgerSum={}",
                        row.getAccountId(), row.getBalance(), row.getLedgerSum());
            }
        }
        return inconsistent;
    }

    /** 합이 0이 아닌 분개 id 목록 반환. 빈 목록이면 모든 분개가 복식부기 균형. */
    @Transactional(readOnly = true)
    public List<String> findUnbalancedJournals() {
        List<String> unbalanced = ledgerEntryRepository.findUnbalancedJournalIds();
        for (String journalId : unbalanced) {
            log.warn("복식부기 위반(분개 합 != 0): journalId={}", journalId);
        }
        return unbalanced;
    }

    /** 시산표: 전 원장 SUM(CREDIT) - SUM(DEBIT). 0이 아니면 시스템 전체 자금 보존 위반. */
    @Transactional(readOnly = true)
    public BigDecimal trialBalance() {
        BigDecimal sum = ledgerEntryRepository.sumTrialBalance();
        if (sum.signum() != 0) {
            log.warn("시산표 불균형: SUM(CREDIT) - SUM(DEBIT) = {}", sum);
        }
        return sum;
    }
}
