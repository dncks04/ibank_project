package com.ibank.domain.transaction.dto;

import com.ibank.domain.transaction.entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransferResponse(
        Long transactionId,
        String idempotencyKey,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        Transaction.TransactionStatus status,
        LocalDateTime createdAt
) {
    public static TransferResponse from(Transaction tx) {
        return new TransferResponse(
                tx.getId(),
                tx.getIdempotencyKey(),
                tx.getFromAccount() != null ? tx.getFromAccount().getAccountNumber() : null,
                tx.getToAccount() != null ? tx.getToAccount().getAccountNumber() : null,
                tx.getAmount(),
                tx.getStatus(),
                tx.getCreatedAt()
        );
    }
}
