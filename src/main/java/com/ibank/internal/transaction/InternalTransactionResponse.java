package com.ibank.internal.transaction;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.hold.entity.AccountHold;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.entity.TransactionDecision;
import com.ibank.global.time.BusinessClock;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 내부 API의 거래 상태 응답. 계약은 {@code docs/contracts/ibank-internal.openapi.yaml}의 TransactionState.
 *
 * <p>금액과 시각을 문자열로 둔다. 계약 형식(금액은 십진 문자열, 시각은 영업 타임존 오프셋이 붙은 ISO-8601)이
 * JSON 라이브러리의 직렬화 설정에 좌우되지 않고, 멱등성 저장소에 저장했다가 다시 읽어도 글자 그대로 같게 하기 위함이다.
 */
public record InternalTransactionResponse(
        Long transactionId,
        String type,
        String channel,
        String status,
        String amount,
        String fromAccountNumber,
        String toAccountNumber,
        Long customerId,
        Hold hold,
        Decision decision,
        String failureCode,
        String resolutionReasonCode,
        String createdAt,
        String completedAt
) {

    public record Hold(String status, String amount, String expiresAt) {}

    public record Decision(String decision, String decisionId, String riskLevel,
                           List<String> reasonCodes, String decisionSource) {}

    /**
     * @param hold 거래의 홀드. 입금처럼 홀드가 없는 거래면 null.
     */
    public static InternalTransactionResponse of(Transaction tx, AccountHold hold, BusinessClock clock) {
        Account from = tx.getFromAccount();
        Account to = tx.getToAccount();
        // 출금·이체는 출금 계좌의 주인, 입금은 입금 계좌의 주인
        Account owned = from != null ? from : to;
        return new InternalTransactionResponse(
                tx.getId(),
                tx.getType().name(),
                tx.getChannel().name(),
                tx.getStatus().name(),
                tx.getAmount().toPlainString(),
                from != null ? from.getAccountNumber() : null,
                to != null ? to.getAccountNumber() : null,
                owned != null ? owned.getOwner().getId() : null,
                hold != null
                        ? new Hold(hold.getStatus().name(), hold.getAmount().toPlainString(),
                                format(hold.getExpiresAt(), clock))
                        : null,
                decisionOf(tx.getDecision()),
                tx.getFailureCode(),
                tx.getResolutionReason(),
                format(tx.getCreatedAt(), clock),
                format(tx.getCompletedAt(), clock));
    }

    private static Decision decisionOf(TransactionDecision decision) {
        if (decision == null || decision.getVerdict() == null) {
            return null;
        }
        return new Decision(
                decision.getVerdict().name(),
                decision.getDecisionId(),
                decision.getRiskLevel() != null ? decision.getRiskLevel().name() : null,
                decision.reasonCodeList(),
                decision.getSource() != null ? decision.getSource().name() : null);
    }

    private static String format(LocalDateTime stored, BusinessClock clock) {
        return stored == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(clock.toBusinessOffset(stored));
    }
}
