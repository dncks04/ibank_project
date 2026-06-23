package com.ibank.domain.account.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record AccountOpenRequest(
        // 멱등성 키: 같은 키로 재요청하면 새 계좌를 만들지 않고 기존 계좌를 반환한다 (중복 생성 방지)
        @NotBlank @Size(max = 64) String idempotencyKey,
        @NotNull @DecimalMin("0.00") BigDecimal initialBalance
) {}
