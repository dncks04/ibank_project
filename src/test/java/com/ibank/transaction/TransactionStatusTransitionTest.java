package com.ibank.transaction;

import com.ibank.domain.transaction.entity.IllegalTransactionTransitionException;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.entity.Transaction.TransactionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static com.ibank.domain.transaction.entity.Transaction.TransactionStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 거래 상태 전이 규칙. 허용된 전이만 통과하고 나머지는 모두 예외여야 한다.
 */
class TransactionStatusTransitionTest {

    /** 명세의 허용 전이표. 구현(enum)과 따로 적어 두어, 한쪽만 바뀌면 테스트가 깨지게 한다. */
    private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(COMPLETED, FAILED, PENDING_REVIEW),
            PENDING_REVIEW, EnumSet.of(COMPLETED, HELD, CANCELLED, FAILED),
            HELD, EnumSet.of(COMPLETED, CANCELLED, EXPIRED),
            COMPLETED, EnumSet.noneOf(TransactionStatus.class),
            FAILED, EnumSet.noneOf(TransactionStatus.class),
            CANCELLED, EnumSet.noneOf(TransactionStatus.class),
            EXPIRED, EnumSet.noneOf(TransactionStatus.class));

    /** 각 목표 상태로 가는 엔티티 메서드. */
    private static final Map<TransactionStatus, Consumer<Transaction>> MOVE = Map.of(
            PENDING_REVIEW, Transaction::submitForReview,
            COMPLETED, Transaction::complete,
            FAILED, tx -> tx.fail("TEST"),
            HELD, Transaction::hold,
            CANCELLED, Transaction::cancel,
            EXPIRED, Transaction::expire);

    @ParameterizedTest(name = "{0}에서 출발")
    @EnumSource(TransactionStatus.class)
    @DisplayName("허용된 전이만 통과하고 나머지는 예외를 던지며 상태가 바뀌지 않는다")
    void onlyAllowedTransitionsPass(TransactionStatus from) {
        for (TransactionStatus to : MOVE.keySet()) {
            Transaction tx = transactionIn(from);
            if (ALLOWED.get(from).contains(to)) {
                MOVE.get(to).accept(tx);
                assertThat(tx.getStatus()).as("%s → %s 허용", from, to).isEqualTo(to);
            } else {
                assertThatThrownBy(() -> MOVE.get(to).accept(tx))
                        .as("%s → %s 금지", from, to)
                        .isInstanceOf(IllegalTransactionTransitionException.class);
                assertThat(tx.getStatus()).as("금지된 전이는 상태를 바꾸지 않는다").isEqualTo(from);
            }
            assertThat(from.canTransitionTo(to)).isEqualTo(ALLOWED.get(from).contains(to));
        }
    }

    @Test
    @DisplayName("종료 상태에서는 어떤 전이도 할 수 없다")
    void terminalStates_haveNoExit() {
        for (TransactionStatus status : EnumSet.of(COMPLETED, FAILED, CANCELLED, EXPIRED)) {
            assertThat(status.isTerminal()).isTrue();
        }
        for (TransactionStatus status : EnumSet.of(PENDING, PENDING_REVIEW, HELD)) {
            assertThat(status.isTerminal()).isFalse();
        }
    }

    @Test
    @DisplayName("완료 시각은 완료될 때만 기록되고, 실패 사유는 실패할 때만 기록된다")
    void completedAtAndFailureCode() {
        Transaction completed = transactionIn(HELD);
        assertThat(completed.getCompletedAt()).isNull();
        completed.complete();
        assertThat(completed.getCompletedAt()).isNotNull();

        Transaction failed = transactionIn(PENDING_REVIEW);
        failed.fail("ACCOUNT_NOT_ACTIVE");
        assertThat(failed.getFailureCode()).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat(failed.getCompletedAt()).isNull();

        Transaction cancelled = transactionIn(HELD);
        cancelled.cancel();
        assertThat(cancelled.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("금액이 묶여 있어야 하는 상태는 PENDING_REVIEW와 HELD뿐이다")
    void statesRequiringHold() {
        assertThat(EnumSet.allOf(TransactionStatus.class).stream().filter(TransactionStatus::requiresActiveHold))
                .containsExactlyInAnyOrder(PENDING_REVIEW, HELD);
    }

    /** 허용된 전이만 밟아 원하는 상태의 거래를 만든다. */
    private static Transaction transactionIn(TransactionStatus status) {
        Transaction tx = Transaction.builder()
                .amount(new BigDecimal("1000"))
                .type(Transaction.TransactionType.WITHDRAWAL)
                .build();
        switch (status) {
            case PENDING -> { }
            case PENDING_REVIEW -> tx.submitForReview();
            case HELD -> { tx.submitForReview(); tx.hold(); }
            case COMPLETED -> tx.complete();
            case FAILED -> tx.fail("TEST");
            case CANCELLED -> { tx.submitForReview(); tx.cancel(); }
            case EXPIRED -> { tx.submitForReview(); tx.hold(); tx.expire(); }
        }
        return tx;
    }
}
