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

    /**
     * 특정 계좌가 출금 또는 입금에 관여한 거래 내역 조회 (페이징).
     * from/to 양쪽을 OR로 검색하고, 날짜 범위 필터를 선택적으로 적용.
     *
     * 상대 계좌번호 표기를 위해 from/to 계좌를 fetch join으로 함께 로딩한다(N+1 방지).
     * 둘 다 ManyToOne이라 페이징과 함께 써도 안전하다. countQuery는 join 없이 별도 지정한다.
     */
    @Query(value = """
            SELECT t FROM Transaction t
            LEFT JOIN FETCH t.fromAccount
            LEFT JOIN FETCH t.toAccount
            WHERE (t.fromAccount.id = :accountId OR t.toAccount.id = :accountId)
              AND (CAST(:from AS timestamp) IS NULL OR t.createdAt >= :from)
              AND (CAST(:to   AS timestamp) IS NULL OR t.createdAt <= :to)
            ORDER BY t.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(t) FROM Transaction t
            WHERE (t.fromAccount.id = :accountId OR t.toAccount.id = :accountId)
              AND (CAST(:from AS timestamp) IS NULL OR t.createdAt >= :from)
              AND (CAST(:to   AS timestamp) IS NULL OR t.createdAt <= :to)
            """)
    Page<Transaction> findByAccountIdAndDateRange(
            @Param("accountId") Long accountId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);
}
