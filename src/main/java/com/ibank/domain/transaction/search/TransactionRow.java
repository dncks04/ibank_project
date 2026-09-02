package com.ibank.domain.transaction.search;

import com.ibank.domain.transaction.entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 거래 검색 결과 한 행. JPA 엔티티가 아니라 조회 전용 projection이므로
 * 영속성 컨텍스트나 지연 로딩과 얽히지 않는다.
 *
 * <p>{@code counterpartAccountNumber}는 애플리케이션에서 계산하지 않고 SQL의 조인과 CASE로 채운다.
 * 같은 값을 자바에서 만드는 코드는 {@code TransactionHistoryResponse.resolveCounterpart()}에 있다.
 */
public record TransactionRow(
        Long id,
        Transaction.TransactionType type,
        Transaction.TransactionStatus status,
        BigDecimal amount,
        String counterpartAccountNumber,
        String description,
        LocalDateTime createdAt
) {
}
