package com.ibank.domain.transaction.search;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 판정용 거래 이력 한 행. 조회 대상 계좌를 기준으로 자금 방향(IN/OUT)과 상대 계좌를 싣는다.
 */
public record DecisionHistoryRow(
        Long id,
        String type,
        String channel,
        String status,
        String direction,
        BigDecimal amount,
        String counterpartAccountNumber,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) {}
