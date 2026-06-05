package com.ibank.domain.ledger.service;

import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.ledger.dto.AccountLedgerBalance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 정합성 검증(reconciliation): 각 계좌의 잔액 스냅샷이 원장 합계와 일치하는지 확인한다.
 * 불변식: account.balance == SUM(CREDIT) - SUM(DEBIT).
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
}
