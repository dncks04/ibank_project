package com.ibank.domain.ledger.service;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.ledger.entity.LedgerDirection;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 정합성 검증(reconciliation): 각 계좌의 잔액 스냅샷이 원장 합계와 일치하는지 확인한다.
 * 불변식: account.balance == SUM(CREDIT) - SUM(DEBIT).
 * 배치/스케줄러에서 주기적으로 호출하여 데이터 무결성을 감시할 수 있다.
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
        Account account = accountRepository.findById(accountId).orElseThrow();
        BigDecimal ledgerSum = ledgerEntryRepository.signedSumByAccountId(accountId, LedgerDirection.CREDIT);
        return account.getBalance().compareTo(ledgerSum) == 0;
    }

    /** 전 계좌 검증 후 불일치 계좌 id 목록 반환. 빈 목록이면 모두 정합. */
    @Transactional(readOnly = true)
    public List<Long> findInconsistentAccounts() {
        List<Long> inconsistent = new ArrayList<>();
        for (Account account : accountRepository.findAll()) {
            BigDecimal ledgerSum =
                    ledgerEntryRepository.signedSumByAccountId(account.getId(), LedgerDirection.CREDIT);
            if (account.getBalance().compareTo(ledgerSum) != 0) {
                inconsistent.add(account.getId());
                log.warn("정합성 불일치: accountId={}, balance={}, ledgerSum={}",
                        account.getId(), account.getBalance(), ledgerSum);
            }
        }
        return inconsistent;
    }
}
