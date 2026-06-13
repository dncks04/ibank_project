package com.ibank.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 이자 계산 설정.
 *
 * @param annualRate    연이율(예: 0.02 = 2%). 일할 이자는 {@code 잔액 × annualRate / dayCountBasis}.
 * @param dayCountBasis 일할 계산 기준 일수(관행상 365). 윤년 무시(ACT/365 Fixed).
 */
@ConfigurationProperties(prefix = "ibank.interest")
public record InterestProperties(BigDecimal annualRate, int dayCountBasis) {

    public InterestProperties {
        if (annualRate == null) {
            annualRate = new BigDecimal("0.02");
        }
        if (dayCountBasis <= 0) {
            dayCountBasis = 365;
        }
    }
}
