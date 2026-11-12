package com.ibank.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * 정기예금 설정.
 *
 * @param annualRate           약정 연이율. 가입 시점의 값이 계좌에 고정된다.
 * @param earlyTerminationRate 중도해지 연이율. 만기 전에 해지하면 약정 금리 대신 이 금리를 적용한다.
 * @param minTermMonths        최소 가입 기간(개월)
 * @param maxTermMonths        최대 가입 기간(개월)
 * @param dayCountBasis        일할 계산 기준 일수
 */
@ConfigurationProperties(prefix = "ibank.time-deposit")
public record TimeDepositProperties(
        @DefaultValue("0.035") BigDecimal annualRate,
        @DefaultValue("0.005") BigDecimal earlyTerminationRate,
        @DefaultValue("1") int minTermMonths,
        @DefaultValue("60") int maxTermMonths,
        @DefaultValue("365") int dayCountBasis
) {}
