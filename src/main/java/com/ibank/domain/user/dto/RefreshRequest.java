package com.ibank.domain.user.dto;

import jakarta.validation.constraints.NotBlank;

/** /refresh, /logout 공통 요청 바디. */
public record RefreshRequest(
        @NotBlank String refreshToken
) {}
