package com.ibank.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * 거래 한도 설정.
 *
 * <ul>
 *   <li>{@code perTransactionMax}: 단건 한도. 모든 거래(이체/입금/출금)의 1회 금액 상한.
 *       컬럼 정밀도(19,2)가 허용하는 비현실적 금액의 요청을 차단하는 1차 방어선.</li>
 *   <li>{@code dailyOutflowMax}: 1일 누적 출금 한도. 한 계좌에서 당일 빠져나간(이체+출금)
 *       금액의 합 상한. 계좌 탈취·오조작 시 피해 규모를 제한한다.</li>
 * </ul>
 *
 * <p>전 계좌 공통 기본 한도이며, 고객 등급별 한도가 필요해지면 계좌/사용자 단위 테이블로 확장한다.
 */
@ConfigurationProperties(prefix = "ibank.limits")
public record TransactionLimitProperties(
        @DefaultValue("10000000") BigDecimal perTransactionMax,
        @DefaultValue("50000000") BigDecimal dailyOutflowMax
) {}
