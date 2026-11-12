package com.ibank.domain.transaction.entity;

import com.ibank.domain.account.entity.Account;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "transactions", indexes = {
    @Index(name = "idx_transaction_from_account_created", columnList = "from_account_id, created_at DESC"),
    @Index(name = "idx_transaction_to_account_created", columnList = "to_account_id, created_at DESC"),
    @Index(name = "idx_transaction_idempotency_key", columnList = "idempotency_key", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 멱등성 키: 중복 이체 방지
    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    // 요청 지문: 같은 멱등성 키가 다른 내용의 요청에 재사용되었는지 식별 (replay 안전성)
    @Column(length = 64)
    private String requestFingerprint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account_id")
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id")
    private Account toAccount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    // 거래 채널. 판정 요청과 이벤트에 실린다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Channel channel;

    @Column(length = 200)
    private String description;

    // 요청 시각
    @Column(nullable = false)
    private LocalDateTime createdAt;

    // 완료 시각. 보류됐다가 나중에 실행된 거래는 요청 시각과 다르다. 1일 한도는 이 시각으로 집계한다.
    private LocalDateTime completedAt;

    // 판정 결과. 판정 전이거나 판정 대상이 아닌 거래(입금)는 null.
    @Embedded
    private TransactionDecision decision;

    // FAILED 사유 코드 (승인 판정 뒤 실행 시점 재검증에 실패한 이유)
    @Column(length = 50)
    private String failureCode;

    // 보류 거래를 실행·취소할 때 호출 서비스가 보낸 사유 코드
    @Column(length = 50)
    private String resolutionReason;

    @Builder
    public Transaction(Account fromAccount, Account toAccount, BigDecimal amount,
                       TransactionType type, String description, Channel channel) {
        this.idempotencyKey = UUID.randomUUID().toString();
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.amount = amount;
        this.type = type;
        this.status = TransactionStatus.PENDING;
        this.channel = channel != null ? channel : Channel.MOBILE;
        this.description = description;
        this.createdAt = LocalDateTime.now();
    }

    public Transaction withIdempotencyKey(String key) {
        this.idempotencyKey = key;
        return this;
    }

    /** 멱등성 키와 함께 요청 지문을 기록한다 (재요청 시 동일 요청인지 검증하기 위함). */
    public Transaction withIdempotency(String key, String requestFingerprint) {
        this.idempotencyKey = key;
        this.requestFingerprint = requestFingerprint;
        return this;
    }

    // ---- 상태 전이 ----
    // 상태는 아래 메서드로만 바뀐다. 허용되지 않은 전이는 IllegalTransactionTransitionException.

    /** 가승인: 금액을 홀드로 묶고 판정을 기다린다. */
    public void submitForReview() {
        transitionTo(TransactionStatus.PENDING_REVIEW);
    }

    /** 완료: 자금 이동과 분개는 호출자가 같은 트랜잭션에서 수행한다. */
    public void complete() {
        transitionTo(TransactionStatus.COMPLETED);
        this.completedAt = LocalDateTime.now();
    }

    /** 실패 (사유 없음). 판정을 거치지 않는 즉시 처리 경로용. */
    public void fail() {
        transitionTo(TransactionStatus.FAILED);
    }

    /** 실패: 승인 판정 뒤 실행 시점 재검증에서 걸렸다. 자금은 이동하지 않았다. */
    public void fail(String failureCode) {
        transitionTo(TransactionStatus.FAILED);
        this.failureCode = failureCode;
    }

    /** 보류: 판정이 HOLD. 금액은 홀드로 묶인 채 실행·취소를 기다린다. */
    public void hold() {
        transitionTo(TransactionStatus.HELD);
    }

    /** 취소: 자금은 이동하지 않는다(원장 분개 없음). 홀드 해제는 호출자가 수행한다. */
    public void cancel() {
        transitionTo(TransactionStatus.CANCELLED);
    }

    /** 만료: 최대 보류 시간이 지났다. 자금은 이동하지 않는다. */
    public void expire() {
        transitionTo(TransactionStatus.EXPIRED);
    }

    /** 판정 결과를 거래에 연결한다. 상태 전이와는 별개다. */
    public void recordDecision(TransactionDecision decision) {
        this.decision = decision;
    }

    /** 보류 거래의 실행·취소 사유를 남긴다. 사유가 없으면(null) 아무것도 하지 않는다. */
    public void recordResolutionReason(String reasonCode) {
        if (reasonCode != null) {
            this.resolutionReason = reasonCode;
        }
    }

    private void transitionTo(TransactionStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new IllegalTransactionTransitionException(this.id, this.status, target);
        }
        this.status = target;
    }

    public enum TransactionType {
        DEPOSIT, WITHDRAWAL, TRANSFER
    }

    public enum Channel {
        MOBILE, TELLER, ATM
    }

    /**
     * 거래 상태와 허용 전이.
     *
     * <pre>
     * PENDING        → COMPLETED / FAILED / PENDING_REVIEW   (PENDING은 저장 전 초기 상태. 입금은 바로 COMPLETED)
     * PENDING_REVIEW → COMPLETED / HELD / CANCELLED / FAILED
     * HELD           → COMPLETED / CANCELLED / EXPIRED
     * COMPLETED, FAILED, CANCELLED, EXPIRED는 종료 상태 (더 바뀌지 않는다)
     * </pre>
     */
    public enum TransactionStatus {
        PENDING, PENDING_REVIEW, HELD, COMPLETED, FAILED, CANCELLED, EXPIRED;

        private Set<TransactionStatus> allowedTargets() {
            return switch (this) {
                case PENDING -> EnumSet.of(COMPLETED, FAILED, PENDING_REVIEW);
                case PENDING_REVIEW -> EnumSet.of(COMPLETED, HELD, CANCELLED, FAILED);
                case HELD -> EnumSet.of(COMPLETED, CANCELLED, EXPIRED);
                case COMPLETED, FAILED, CANCELLED, EXPIRED -> EnumSet.noneOf(TransactionStatus.class);
            };
        }

        public boolean canTransitionTo(TransactionStatus target) {
            return allowedTargets().contains(target);
        }

        /** 종료 상태(더 바뀌지 않는 상태)인지. */
        public boolean isTerminal() {
            return allowedTargets().isEmpty();
        }

        /** 금액이 홀드로 묶여 있어야 하는 상태인지. */
        public boolean requiresActiveHold() {
            return this == PENDING_REVIEW || this == HELD;
        }
    }
}
