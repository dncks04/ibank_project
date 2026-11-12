package com.ibank.domain.transaction.dto;

import com.ibank.domain.transaction.entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 거래 응답.
 *
 * @param status      거래의 현재 상태. 이체·출금은 COMPLETED(실행됨), HELD(보류됨),
 *                    PENDING_REVIEW(판정 대기 중 — 같은 멱등성 키로 다시 요청하면 최신 상태를 받는다) 중 하나로 응답한다.
 * @param reasonCodes 보류 사유 코드. HELD일 때 판정이 준 사유가 실리고, 그 외에는 빈 목록이다.
 *                    창구·앱 화면이 이 코드를 보고 확인 질문을 띄운다.
 */
public record TransferResponse(
        Long transactionId,
        String idempotencyKey,
        String fromAccountNumber,
        String toAccountNumber,
        BigDecimal amount,
        Transaction.TransactionStatus status,
        LocalDateTime createdAt,
        List<String> reasonCodes
) {
    public static TransferResponse from(Transaction tx) {
        return new TransferResponse(
                tx.getId(),
                tx.getIdempotencyKey(),
                tx.getFromAccount() != null ? tx.getFromAccount().getAccountNumber() : null,
                tx.getToAccount() != null ? tx.getToAccount().getAccountNumber() : null,
                tx.getAmount(),
                tx.getStatus(),
                tx.getCreatedAt(),
                tx.getStatus() == Transaction.TransactionStatus.HELD && tx.getDecision() != null
                        ? tx.getDecision().reasonCodeList()
                        : List.of()
        );
    }
}
