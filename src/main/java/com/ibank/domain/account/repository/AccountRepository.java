package com.ibank.domain.account.repository;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.ledger.dto.AccountLedgerBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByAccountNumber(String accountNumber);

    // 개설 멱등성: 같은 키로 이미 만들어진 계좌를 찾아 replay 여부를 판단한다.
    Optional<Account> findByOpenIdempotencyKey(String openIdempotencyKey);

    // 비관적 락: 잔액 변경이 보장되어야 할 때 (이체 등 고위험 연산)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.accountNumber = :accountNumber")
    Optional<Account> findByAccountNumberWithLock(@Param("accountNumber") String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    List<Account> findAllByOwnerId(Long ownerId);

    /**
     * 한 계좌의 잔액과 원장 합계를 단일 쿼리(단일 스냅샷)로 함께 조회한다. 정합성 검증의 read skew 방지용.
     * LEFT JOIN이므로 원장 항목이 없는 계좌도 ledgerSum=0으로 조회된다.
     */
    @Query("""
            SELECT a.id AS accountId, a.balance AS balance,
                   COALESCE(SUM(CASE WHEN l.direction = com.ibank.domain.ledger.entity.LedgerDirection.CREDIT
                                     THEN l.amount ELSE -l.amount END), 0) AS ledgerSum
            FROM Account a
            LEFT JOIN LedgerEntry l ON l.account = a
            WHERE a.id = :accountId
            GROUP BY a.id, a.balance
            """)
    Optional<AccountLedgerBalance> findAccountLedgerBalance(@Param("accountId") Long accountId);

    /**
     * 전 계좌의 잔액과 원장 합계를 단일 쿼리(단일 스냅샷)로 조회한다.
     * 모든 계좌가 동일 스냅샷에서 계산되므로 read skew로 인한 오탐이 발생하지 않는다.
     */
    @Query("""
            SELECT a.id AS accountId, a.balance AS balance,
                   COALESCE(SUM(CASE WHEN l.direction = com.ibank.domain.ledger.entity.LedgerDirection.CREDIT
                                     THEN l.amount ELSE -l.amount END), 0) AS ledgerSum
            FROM Account a
            LEFT JOIN LedgerEntry l ON l.account = a
            GROUP BY a.id, a.balance
            """)
    List<AccountLedgerBalance> findAllAccountLedgerBalances();
}
