package com.ibank.domain.transaction.search;

import java.math.BigDecimal;

/**
 * 월별 거래 요약 한 행. 완료(COMPLETED)된 거래만 집계한다.
 *
 * <p>거래가 하나도 없는 달은 행 자체가 나오지 않는다(GROUP BY 결과). 빈 달을 0으로 채우는 것은
 * 표현 계층의 몫으로 남겨 둔다.
 */
public record MonthlySummaryRow(
        int month,
        long count,
        BigDecimal totalIn,
        BigDecimal totalOut
) {
}
