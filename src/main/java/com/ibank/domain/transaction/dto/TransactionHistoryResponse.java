package com.ibank.domain.transaction.dto;

import com.ibank.domain.transaction.entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionHistoryResponse(
        Long id,
        Transaction.TransactionType type,
        Transaction.TransactionStatus status,
        BigDecimal amount,
        String counterpartAccountNumber,
        String description,
        LocalDateTime createdAt
) {
    /**
     * @param myAccountId 조회 주체 계좌 ID — 상대방 계좌번호를 결정하는 데 사용
     */
    public static TransactionHistoryResponse from(Transaction tx, Long myAccountId) {
        String counterpart = resolveCounterpart(tx, myAccountId);
        return new TransactionHistoryResponse(
                tx.getId(),
                tx.getType(),
                tx.getStatus(),
                tx.getAmount(),
                counterpart,
                tx.getDescription(),
                tx.getCreatedAt()
        );
    }

    private static String resolveCounterpart(Transaction tx, Long myAccountId) {
        return switch (tx.getType()) {
            case DEPOSIT -> tx.getFromAccount() != null
                    ? tx.getFromAccount().getAccountNumber() : null;
            case WITHDRAWAL -> tx.getToAccount() != null
                    ? tx.getToAccount().getAccountNumber() : null;
            case TRANSFER -> {
                boolean isSender = tx.getFromAccount() != null
                        && tx.getFromAccount().getId().equals(myAccountId);
                yield isSender
                        ? (tx.getToAccount() != null ? tx.getToAccount().getAccountNumber() : null)
                        : (tx.getFromAccount() != null ? tx.getFromAccount().getAccountNumber() : null);
            }
        };
    }
}
