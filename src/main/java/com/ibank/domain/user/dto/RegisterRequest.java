package com.ibank.domain.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(min = 4, max = 20) String loginId,
        @NotBlank @Size(min = 8, max = 50) String password,
        @NotBlank @Size(max = 30) String name,
        @NotBlank @Email String email
) {}
