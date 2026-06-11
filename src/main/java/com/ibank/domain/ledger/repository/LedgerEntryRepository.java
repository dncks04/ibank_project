package com.ibank.domain.ledger.repository;

import com.ibank.domain.ledger.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    long countByAccountId(Long accountId);

    /**
     * 합이 0이 아닌 분개(journal) id 목록. 복식부기 위반(돈이 생기거나 사라진 거래) → 조사 대상.
     * 시스템 leg를 포함한 모든 leg를 분개 단위로 합산하므로, 잔액과 원장이 함께 잘못되어
     * 계좌별 검증(balance == ledgerSum)을 통과하는 버그도 여기서 탐지된다.
     */
    @Query("""
            SELECT l.journalId
            FROM LedgerEntry l
            GROUP BY l.journalId
            HAVING SUM(CASE WHEN l.direction = com.ibank.domain.ledger.entity.LedgerDirection.CREDIT
                            THEN l.amount ELSE -l.amount END) <> 0
            """)
    List<String> findUnbalancedJournalIds();

    /**
     * 시산표(trial balance): 전 원장의 SUM(CREDIT) - SUM(DEBIT).
     * 모든 분개가 균형이면 0. 0이 아니면 시스템 전체에서 자금 보존이 깨진 것.
     */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN l.direction = com.ibank.domain.ledger.entity.LedgerDirection.CREDIT
                                     THEN l.amount ELSE -l.amount END), 0)
            FROM LedgerEntry l
            """)
    BigDecimal sumTrialBalance();
}
