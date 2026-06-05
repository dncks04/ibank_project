package com.ibank.domain.ledger.repository;

import com.ibank.domain.ledger.entity.LedgerDirection;
import com.ibank.domain.ledger.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /** 계좌의 원장 합계: SUM(CREDIT) - SUM(DEBIT). 정합성 검증에 사용. */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN l.direction = :credit THEN l.amount ELSE -l.amount END), 0)
            FROM LedgerEntry l
            WHERE l.account.id = :accountId
            """)
    BigDecimal signedSumByAccountId(@Param("accountId") Long accountId,
                                    @Param("credit") LedgerDirection credit);

    long countByAccountId(Long accountId);
}
