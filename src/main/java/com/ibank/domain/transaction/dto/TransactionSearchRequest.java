package com.ibank.domain.transaction.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public record TransactionSearchRequest(
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        int page,
        int size
) {
    public TransactionSearchRequest {
        if (page < 0) page = 0;
        if (size < 1 || size > 100) size = 20;
    }

    public static TransactionSearchRequest of(LocalDate from, LocalDate to, int page, int size) {
        return new TransactionSearchRequest(from, to, page, size);
    }
}
