package com.ibank.domain.fraud.dto;

import com.ibank.domain.fraud.entity.FraudAlert;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 운영자 검토 큐에 노출하는 이상거래 경보 표현. */
public record FraudAlertResponse(
        Long id,
        Long accountId,
        Long transactionId,
        String triggeredRules,
        BigDecimal amount,
        String detail,
        FraudAlert.Status status,
        LocalDateTime createdAt
) {
    public static FraudAlertResponse from(FraudAlert alert) {
        return new FraudAlertResponse(
                alert.getId(),
                alert.getAccountId(),
                alert.getTransactionId(),
                alert.getTriggeredRules(),
                alert.getAmount(),
                alert.getDetail(),
                alert.getStatus(),
                alert.getCreatedAt()
        );
    }
}
