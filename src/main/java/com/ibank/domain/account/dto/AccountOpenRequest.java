package com.ibank.domain.account.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AccountOpenRequest(
        @NotNull @DecimalMin("0.00") BigDecimal initialBalance
) {}
