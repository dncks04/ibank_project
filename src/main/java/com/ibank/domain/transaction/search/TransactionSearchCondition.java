package com.ibank.domain.transaction.search;

import com.ibank.domain.transaction.entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 거래 검색 조건. {@code accountId}를 뺀 나머지는 전부 선택이며, null이면 해당 조건이 SQL에서 빠진다.
 *
 * <p>{@code accountId}는 클라이언트가 보내는 값이 아니라 서비스가 계좌 소유권을 검증한 뒤
 * {@link #withAccountId(Long)}로 주입한다. 매퍼는 검증이 끝난 계좌만 받는다.
 */
public record TransactionSearchCondition(
        Long accountId,
        LocalDateTime from,
        LocalDateTime to,
        List<Transaction.TransactionType> types,
        List<Transaction.TransactionStatus> statuses,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Direction direction,
        String keyword,
        SortKey sort,
        int page,
        int size
) {
    public TransactionSearchCondition {
        if (page < 0) page = 0;
        if (size < 1 || size > 100) size = 20;
        if (sort == null) sort = SortKey.CREATED_AT;
    }

    /** 소유권 검증을 마친 계좌 ID를 채운 새 조건을 만든다. */
    public TransactionSearchCondition withAccountId(Long accountId) {
        return new TransactionSearchCondition(accountId, from, to, types, statuses,
                minAmount, maxAmount, direction, keyword, sort, page, size);
    }

    /**
     * 건너뛸 행 수. SQL에서 {@code OFFSET #{page} * #{size}}로 곱하면 PostgreSQL이 바인딩
     * 파라미터의 타입을 추론하지 못해 실패할 수 있어 여기서 계산해 넘긴다.
     *
     * <p>MyBatis는 record의 인자 없는 메서드를 이름 그대로 프로퍼티로 등록한다(JavaBean 규약을
     * 적용하지 않는다). 따라서 {@code #{offset}}으로 쓰려면 이름이 {@code getOffset}이 아니라
     * {@code offset}이어야 한다.
     */
    public long offset() {
        return (long) page * size;
    }

    /** 조회 주체 계좌를 기준으로 한 자금 방향. */
    public enum Direction {
        /** 들어온 거래 (해당 계좌가 수취인) */
        IN,
        /** 나간 거래 (해당 계좌가 출금인) */
        OUT
    }

    /**
     * 정렬 키. 컬럼명을 문자열로 받지 않고 enum으로 닫아두어,
     * 사용자 입력이 SQL 식별자 자리로 흘러들 여지를 없앤다.
     */
    public enum SortKey {
        CREATED_AT,
        AMOUNT
    }
}
