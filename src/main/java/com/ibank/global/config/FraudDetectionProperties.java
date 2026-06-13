package com.ibank.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * 이상거래 탐지(FDS) 설정. 기본 비활성 — 켜면 이체가 룰에 걸릴 때 보류(HELD)된다.
 *
 * @param enabled              탐지 활성화 여부.
 * @param velocityWindowMinutes velocity 룰의 관찰 창(분).
 * @param velocityMaxOutgoing   창 안에서 허용하는 출금성 거래 수(이 수를 초과하면 발동).
 * @param nightHighValueMin     심야 고액 룰의 금액 임계치.
 * @param nightStartHour        심야 구간 시작 시(포함).
 * @param nightEndHour          심야 구간 종료 시(미포함).
 * @param newPayeeHighValueMin  신규 수취인 고액 룰의 금액 임계치.
 */
@ConfigurationProperties(prefix = "ibank.fraud")
public record FraudDetectionProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5") int velocityWindowMinutes,
        @DefaultValue("5") int velocityMaxOutgoing,
        @DefaultValue("5000000") BigDecimal nightHighValueMin,
        @DefaultValue("0") int nightStartHour,
        @DefaultValue("5") int nightEndHour,
        @DefaultValue("3000000") BigDecimal newPayeeHighValueMin
) {}
