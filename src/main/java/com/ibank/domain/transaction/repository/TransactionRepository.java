package com.ibank.domain.transaction.repository;

import com.ibank.domain.transaction.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /**
     * 거래 행을 잠그고 읽는다. 같은 거래에 대한 확정·실행·취소·만료를 직렬화한다.
     *
     * <p>한 트랜잭션에서 이 거래를 <b>처음 읽을 때</b> 써야 한다. 락 없이 먼저 읽은 인스턴스가 영속성 컨텍스트에
     * 있으면 이 쿼리는 그 인스턴스를 그대로 돌려주므로(행은 잠기지만 상태는 옛것), 잠근 뒤의 상태 확인이 무의미해진다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") Long id);

    /**
     * 가승인 뒤 오래도록 판정이 반영되지 않은(PENDING_REVIEW로 남은) 거래의 ID를 오래된 순으로 찾는다. 복구 배치용.
     * 부분 인덱스 (created_at) WHERE PENDING_REVIEW를 탄다.
     */
    @Query("""
            SELECT t.id FROM Transaction t
            WHERE t.status = com.ibank.domain.transaction.entity.Transaction.TransactionStatus.PENDING_REVIEW
              AND t.createdAt < :cutoff
            ORDER BY t.createdAt
            """)
    List<Long> findStalePendingReviewIds(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /**
     * 기간 내에 <b>완료된</b> 출금(출금·이체의 출금 측) 금액 합계. 1일 한도 검증용.
     * 이체·출금만 센다. 정기예금 가입·해지처럼 고객 자신의 계좌 사이를 오가는 자금은 한도 대상이 아니다.
     *
     * <p>요청 시각(created_at)이 아니라 완료 시각(completed_at)으로 센다. 보류됐다가 다른 날 실행된 거래를
     * 실제로 돈이 나간 날의 한도에 잡기 위함이다. 부분 인덱스 (from_account_id, completed_at) WHERE COMPLETED를 탄다.
     */
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
            WHERE t.fromAccount.id = :accountId
              AND t.status = com.ibank.domain.transaction.entity.Transaction.TransactionStatus.COMPLETED
              AND t.type IN (com.ibank.domain.transaction.entity.Transaction.TransactionType.TRANSFER,
                             com.ibank.domain.transaction.entity.Transaction.TransactionType.WITHDRAWAL)
              AND t.completedAt >= :from AND t.completedAt < :to
            """)
    BigDecimal sumCompletedOutflow(
            @Param("accountId") Long accountId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

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
