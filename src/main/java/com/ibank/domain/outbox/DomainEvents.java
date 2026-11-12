package com.ibank.domain.outbox;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.hold.entity.AccountHold;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.entity.TransactionDecision;
import com.ibank.global.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 거래·계좌의 상태 변화를 Outbox 이벤트로 기록한다. 페이로드의 모양은 계약
 * ({@code docs/contracts/ibank-internal.openapi.yaml}의 TransactionEventPayload 등)을 따른다.
 *
 * <p>상태를 바꾸는 트랜잭션 안에서, 상태를 바꾼 <b>뒤에</b> 호출한다(페이로드에 바뀐 상태가 실린다).
 * 금액은 십진 문자열, 시각은 영업 타임존 오프셋이 붙은 ISO-8601이다.
 */
@Component
@RequiredArgsConstructor
public class DomainEvents {

    private final OutboxRecorder recorder;
    private final BusinessClock businessClock;

    /**
     * 이체·출금 거래의 상태 변화.
     *
     * @param hold       거래의 홀드(만료 시각을 싣는다). 없으면 null.
     * @param reasonCode 실행·취소 요청에 실려 온 사유, 또는 FAILED의 재검증 실패 코드. 없으면 null.
     */
    public void transaction(EventType type, Transaction tx, AccountHold hold, String reasonCode) {
        Account from = tx.getFromAccount();
        Account to = tx.getToAccount();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", tx.getId());
        payload.put("type", tx.getType().name());
        payload.put("channel", tx.getChannel().name());
        payload.put("status", tx.getStatus().name());
        payload.put("fromAccountNumber", from.getAccountNumber());
        payload.put("toAccountNumber", to != null ? to.getAccountNumber() : null);
        payload.put("amount", money(tx.getAmount()));
        payload.put("customerId", from.getOwner().getId());
        payload.put("decision", decisionOf(tx.getDecision()));
        payload.put("holdExpiresAt", hold != null ? time(hold.getExpiresAt()) : null);
        payload.put("reasonCode", reasonCode);
        recorder.record(type, String.valueOf(tx.getId()), payload);
    }

    /** 입금 완료. 판정 대상이 아니므로 판정·홀드 정보가 없다. */
    public void deposit(Transaction tx, Account account) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", tx.getId());
        payload.put("accountNumber", account.getAccountNumber());
        payload.put("amount", money(tx.getAmount()));
        payload.put("customerId", account.getOwner().getId());
        payload.put("channel", tx.getChannel().name());
        recorder.record(EventType.DEPOSIT, String.valueOf(tx.getId()), payload);
    }

    /**
     * 계좌의 상태 변화(개설, 해지, 지급정지 등).
     *
     * @param initialBalance 개설 이벤트에만. 그 외에는 null.
     * @param reasonCode     지급정지·해제 사유. 그 외에는 null.
     */
    public void account(EventType type, Account account, BigDecimal initialBalance, String reasonCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountNumber", account.getAccountNumber());
        payload.put("customerId", account.getOwner().getId());
        payload.put("accountType", "DEMAND");
        payload.put("initialBalance", initialBalance != null ? money(initialBalance) : null);
        payload.put("reasonCode", reasonCode);
        recorder.record(type, account.getAccountNumber(), payload);
    }

    private Map<String, Object> decisionOf(TransactionDecision decision) {
        if (decision == null || decision.getVerdict() == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("decision", decision.getVerdict().name());
        map.put("decisionId", decision.getDecisionId());
        map.put("riskLevel", decision.getRiskLevel() != null ? decision.getRiskLevel().name() : null);
        map.put("reasonCodes", decision.reasonCodeList());
        map.put("decisionSource", decision.getSource() != null ? decision.getSource().name() : null);
        return map;
    }

    /**
     * 금액을 소수 둘째 자리까지 맞춘 문자열로. DB에서 읽은 값(300000.00)과 요청에서 온 값(300000)이
     * 같은 금액인데 다른 문자열로 나가지 않게 한다.
     */
    private static String money(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private String time(LocalDateTime stored) {
        return stored == null ? null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(businessClock.toBusinessOffset(stored));
    }
}
