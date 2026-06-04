package com.ibank.domain.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record DepositRequest(
        @NotBlank String accountNumber,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank String idempotencyKey,
        String description
) {}
