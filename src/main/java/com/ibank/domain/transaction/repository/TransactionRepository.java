package com.ibank.domain.transaction.repository;

import com.ibank.domain.transaction.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * 기간 내 특정 계좌에서 빠져나간(출금·이체의 출금 측) 거래 금액 합계. 1일 한도 검증용.
     * fromAccount가 이 계좌인 거래만 집계하므로 TRANSFER·WITHDRAWAL이 포함되고 DEPOSIT은 제외된다.
     * (from_account_id, created_at) 인덱스를 탄다.
     */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
            WHERE t.fromAccount.id = :accountId
              AND t.status = :status
              AND t.createdAt >= :from AND t.createdAt < :to
            """)
    BigDecimal sumOutflowAmount(
            @Param("accountId") Long accountId,
            @Param("status") Transaction.TransactionStatus status,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 기간 내 이 계좌에서 출발한 거래 건수. 이상거래 탐지의 velocity(단시간 다회) 룰용. */
    @Query("""
            SELECT COUNT(t) FROM Transaction t
            WHERE t.fromAccount.id = :accountId
              AND t.createdAt >= :since
            """)
    long countOutgoingSince(@Param("accountId") Long accountId, @Param("since") LocalDateTime since);

    /** 이 계좌가 상대 계좌로 과거에 이체를 완료한 적이 있는지. 신규 수취인(new payee) 룰용. */
    @Query("""
            SELECT COUNT(t) > 0 FROM Transaction t
            WHERE t.fromAccount.id = :fromAccountId
              AND t.toAccount.id = :toAccountId
              AND t.status = :status
            """)
    boolean hasTransferTo(@Param("fromAccountId") Long fromAccountId,
                          @Param("toAccountId") Long toAccountId,
                          @Param("status") Transaction.TransactionStatus status);

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
