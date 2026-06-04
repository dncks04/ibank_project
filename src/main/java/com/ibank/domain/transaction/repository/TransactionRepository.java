package com.ibank.domain.transaction.repository;

import com.ibank.domain.transaction.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    boolean existsByIdempotencyKey(String idempotencyKey);

    /**
     * 특정 계좌가 출금 또는 입금에 관여한 거래 내역 조회 (페이징).
     * from/to 양쪽을 OR로 검색하고, 날짜 범위 필터를 선택적으로 적용.
     */
    @Query("""
            SELECT t FROM Transaction t
            WHERE (t.fromAccount.id = :accountId OR t.toAccount.id = :accountId)
              AND (:from IS NULL OR t.createdAt >= :from)
              AND (:to   IS NULL OR t.createdAt <= :to)
            ORDER BY t.createdAt DESC
            """)
    Page<Transaction> findByAccountIdAndDateRange(
            @Param("accountId") Long accountId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);
}
