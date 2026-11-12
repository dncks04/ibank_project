package com.ibank.domain.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record RegisterRequest(
        @NotBlank @Size(min = 4, max = 20) String loginId,
        @NotBlank @Size(min = 8, max = 50) String password,
        @NotBlank @Size(max = 30) String name,
        @NotBlank @Email String email,
        // 생년월일(yyyy-MM-dd). 고령 고객 보호 정책의 기준이 되므로 필수다.
        @NotNull @Past LocalDate birthDate
) {}
