package com.ibank.transaction;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.entity.InsufficientBalanceException;
import com.ibank.domain.account.exception.InactiveAccountException;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.decision.FraudDecisionPort;
import com.ibank.domain.decision.FraudDecisionPort.Decision;
import com.ibank.domain.decision.FraudDecisionPort.Source;
import com.ibank.domain.decision.FraudDecisionPort.Verdict;
import com.ibank.domain.hold.entity.AccountHold;
import com.ibank.domain.hold.repository.AccountHoldRepository;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.ledger.service.ReconciliationService;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.entity.Transaction.TransactionStatus;
import com.ibank.domain.transaction.exception.IdempotencyKeyConflictException;
import com.ibank.domain.transaction.exception.TransactionNotHeldException;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransactionProcessor;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.support.DatabaseCleaner;
import com.ibank.support.ScriptedFraudDecisionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2단계 거래 처리(가승인 → 판정 → 확정)의 시나리오와 동시성.
 *
 * <p>판정은 {@link ScriptedFraudDecisionPort}로 테스트가 정한다. 이체·출금이 판정에 따라 완료·보류되고,
 * 보류된 거래가 실행·취소되는 동안 원장 불변식(분개 합 0, 잔액 == 원장 합계)과
 * 홀드 불변식(가용잔액 ≥ 0, 묶인 금액은 다른 출금으로 빠지지 않음)이 유지되는지 본다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TwoPhaseTransactionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class ScriptedPortConfig {
        @Bean
        @Primary
        ScriptedFraudDecisionPort scriptedFraudDecisionPort() {
            return new ScriptedFraudDecisionPort();
        }
    }

    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private ScriptedFraudDecisionPort fds;
    @Autowired private TransferService transferService;
    @Autowired private TransactionProcessor processor;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountHoldRepository holdRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final BigDecimal INITIAL = new BigDecimal("1000000");

    private Long userId;
    private Long otherUserId;
    private String accA;
    private String accB;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        fds.reset();

        userId = saveUser("twophase-user", "twophase@test.com");
        otherUserId = saveUser("twophase-other", "twophase-other@test.com");
        accA = open(userId, INITIAL);
        accB = open(userId, BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------
    // 시나리오 1: 정상 이체
    // ------------------------------------------------------------------

    @Test
    @DisplayName("시나리오 1 - 판정 APPROVE: 거래 COMPLETED, 홀드 CONSUMED, 분개 합 0")
    void approve_completesAndConsumesHold() {
        fds.approveAll();

        TransferResponse response = transferService.transfer(userId, transfer(accA, accB, "300000"));

        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(response.reasonCodes()).isEmpty();
        assertThat(balanceOf(accA)).isEqualByComparingTo("700000");
        assertThat(balanceOf(accB)).isEqualByComparingTo("300000");
        assertThat(availableOf(accA)).isEqualByComparingTo("700000"); // 홀드가 소비되어 묶인 금액이 남지 않는다

        Transaction tx = transactionRepository.findById(response.transactionId()).orElseThrow();
        assertThat(tx.getCompletedAt()).isNotNull();
        assertThat(tx.getChannel()).isEqualTo(Transaction.Channel.MOBILE);
        assertThat(tx.getDecision().getVerdict()).isEqualTo(Verdict.APPROVE);
        assertThat(tx.getDecision().getSource()).isEqualTo(Source.FDS);
        assertThat(tx.getDecision().getDecisionId()).isEqualTo("decision-" + tx.getId());
        assertThat(holdOf(tx.getId()).getStatus()).isEqualTo(AccountHold.Status.CONSUMED);

        assertLedgerConsistent();
    }

    @Test
    @DisplayName("판정 요청에는 계약서의 필드가 그대로 실린다")
    void evaluationRequest_carriesContractFields() {
        TransferRequest request = transfer(accA, accB, "1500");

        TransferResponse response = transferService.transfer(userId, request);

        assertThat(fds.requests()).singleElement().satisfies(sent -> {
            assertThat(sent.transactionId()).isEqualTo(response.transactionId());
            assertThat(sent.idempotencyKey()).isEqualTo(request.idempotencyKey());
            assertThat(sent.type()).isEqualTo(Transaction.TransactionType.TRANSFER);
            assertThat(sent.channel()).isEqualTo(Transaction.Channel.MOBILE);
            assertThat(sent.fromAccountNumber()).isEqualTo(accA);
            assertThat(sent.toAccountNumber()).isEqualTo(accB);
            assertThat(sent.amount()).isEqualByComparingTo("1500");
            assertThat(sent.customerId()).isEqualTo(userId);
            // 요청 시각은 영업 타임존(Asia/Seoul) 오프셋으로 나간다
            assertThat(sent.requestedAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
            assertThat(Duration.between(sent.requestedAt().toInstant(), java.time.Instant.now()).abs())
                    .isLessThan(Duration.ofMinutes(1));
        });
    }

    @Test
    @DisplayName("판정을 기다리는 동안 트랜잭션도 계좌 락도 쥐고 있지 않고, 거래는 이미 가승인 상태로 커밋돼 있다")
    void duringEvaluation_noLockNoTransaction() {
        AtomicReference<Throwable> hookFailure = new AtomicReference<>();
        fds.duringEvaluation(request -> {
            try {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                        .as("판정 호출 스레드에 열린 트랜잭션이 없어야 한다").isFalse();
                // 다른 커넥션에서 두 계좌 행을 기다림 없이(NOWAIT) 잠글 수 있어야 한다 = 1단계의 락이 풀렸다
                for (String number : List.of(accA, accB)) {
                    jdbcTemplate.queryForObject(
                            "SELECT id FROM accounts WHERE account_number = ? FOR UPDATE NOWAIT", Long.class, number);
                }
                // 1단계는 이미 커밋됐다: 거래 PENDING_REVIEW, 홀드 ACTIVE, 원장잔액 그대로, 가용잔액만 감소
                Transaction pending = transactionRepository.findById(request.transactionId()).orElseThrow();
                assertThat(pending.getStatus()).isEqualTo(TransactionStatus.PENDING_REVIEW);
                assertThat(holdOf(pending.getId()).getStatus()).isEqualTo(AccountHold.Status.ACTIVE);
                assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL);
                assertThat(availableOf(accA)).isEqualByComparingTo("700000");
            } catch (Throwable t) {
                hookFailure.set(t);
            }
        });

        transferService.transfer(userId, transfer(accA, accB, "300000"));

        assertThat(hookFailure.get()).as("판정 중 상태 검증").isNull();
        assertThat(fds.requests()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 시나리오 2·3: 보류 후 실행 / 취소
    // ------------------------------------------------------------------

    @Test
    @DisplayName("시나리오 2 - 판정 HOLD: 가용잔액만 줄고, 실행하면 COMPLETED가 된다")
    void hold_thenExecute() {
        fds.holdAll("NEW_PAYEE_HIGH_VALUE", "DEPOSIT_CLOSED_THEN_TRANSFER");
        long ledgerBefore = ledgerEntryRepository.count();

        TransferResponse held = transferService.transfer(userId, transfer(accA, accB, "300000"));

        // 보류: 응답에 사유 코드가 실리고, 원장잔액·원장은 그대로, 가용잔액만 줄어든다
        assertThat(held.status()).isEqualTo(TransactionStatus.HELD);
        assertThat(held.reasonCodes()).containsExactly("NEW_PAYEE_HIGH_VALUE", "DEPOSIT_CLOSED_THEN_TRANSFER");
        assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL);
        assertThat(balanceOf(accB)).isEqualByComparingTo("0");
        assertThat(availableOf(accA)).isEqualByComparingTo("700000");
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerBefore);
        assertThat(holdOf(held.transactionId()).getStatus()).isEqualTo(AccountHold.Status.ACTIVE);
        Transaction tx = transactionRepository.findById(held.transactionId()).orElseThrow();
        assertThat(tx.getDecision().getVerdict()).isEqualTo(Verdict.HOLD);
        assertThat(tx.getDecision().getRiskLevel()).isEqualTo(FraudDecisionPort.RiskLevel.DANGER);
        assertThat(tx.getCompletedAt()).isNull();

        TransferResponse executed = processor.executeHeld(held.transactionId());

        assertThat(executed.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(balanceOf(accA)).isEqualByComparingTo("700000");
        assertThat(balanceOf(accB)).isEqualByComparingTo("300000");
        assertThat(availableOf(accA)).isEqualByComparingTo("700000");
        assertThat(holdOf(held.transactionId()).getStatus()).isEqualTo(AccountHold.Status.CONSUMED);
        assertThat(transactionRepository.findById(held.transactionId()).orElseThrow().getCompletedAt()).isNotNull();
        assertLedgerConsistent();
    }

    @Test
    @DisplayName("시나리오 3 - 판정 HOLD 후 취소: 가용잔액이 원복되고 분개가 생기지 않는다")
    void hold_thenCancel() {
        fds.holdAll("VELOCITY");
        long ledgerBefore = ledgerEntryRepository.count();
        TransferResponse held = transferService.transfer(userId, transfer(accA, accB, "300000"));
        assertThat(availableOf(accA)).isEqualByComparingTo("700000");

        TransferResponse cancelled = processor.cancelHeld(held.transactionId());

        assertThat(cancelled.status()).isEqualTo(TransactionStatus.CANCELLED);
        assertThat(cancelled.reasonCodes()).isEmpty(); // 사유 코드는 보류 중인 거래의 응답에만 실린다
        assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL);
        assertThat(availableOf(accA)).isEqualByComparingTo(INITIAL);
        assertThat(balanceOf(accB)).isEqualByComparingTo("0");
        assertThat(holdOf(held.transactionId()).getStatus()).isEqualTo(AccountHold.Status.RELEASED);
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerBefore);
        assertLedgerConsistent();
    }

    @Test
    @DisplayName("출금도 같은 흐름을 탄다: 보류되면 금액이 묶이고, 실행하면 클리어링 계정과 분개된다")
    void withdrawal_followsSameFlow() {
        fds.holdAll("LARGE_CASH_WITHDRAWAL");

        TransferResponse held = transferService.withdraw(userId, withdraw(accA, "400000"));

        assertThat(held.status()).isEqualTo(TransactionStatus.HELD);
        assertThat(held.toAccountNumber()).isNull();
        assertThat(fds.requests()).singleElement().satisfies(sent -> {
            assertThat(sent.type()).isEqualTo(Transaction.TransactionType.WITHDRAWAL);
            assertThat(sent.toAccountNumber()).isNull();
        });
        assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL);
        assertThat(availableOf(accA)).isEqualByComparingTo("600000");

        processor.executeHeld(held.transactionId());

        assertThat(balanceOf(accA)).isEqualByComparingTo("600000");
        assertLedgerConsistent(); // 고객 DEBIT + 클리어링 CREDIT → 시산표 0
    }

    // ------------------------------------------------------------------
    // 시나리오 5: 보류 중 이중 인출 시도
    // ------------------------------------------------------------------

    @Test
    @DisplayName("시나리오 5 - 보류된 금액은 다른 출금·이체로 빼 갈 수 없다")
    void heldAmount_cannotBeSpentElsewhere() {
        fds.holdAll("NEW_PAYEE_HIGH_VALUE");
        TransferResponse held = transferService.transfer(userId, transfer(accA, accB, "900000"));
        assertThat(held.status()).isEqualTo(TransactionStatus.HELD);

        fds.approveAll();
        // 원장잔액은 1,000,000이지만 가용잔액은 100,000뿐이다
        assertThatThrownBy(() -> transferService.withdraw(userId, withdraw(accA, "100001")))
                .isInstanceOf(InsufficientBalanceException.class);
        assertThatThrownBy(() -> transferService.transfer(userId, transfer(accA, accB, "500000")))
                .isInstanceOf(InsufficientBalanceException.class);

        transferService.withdraw(userId, withdraw(accA, "100000")); // 가용잔액까지는 가능

        assertThat(balanceOf(accA)).isEqualByComparingTo("900000");
        assertThat(availableOf(accA)).isEqualByComparingTo("0");
        // 거절된 요청은 거래도 홀드도 남기지 않는다 (가승인 단계에서 롤백)
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(holdRepository.count()).isEqualTo(2);

        // 묶여 있던 금액은 온전히 남아 있어 보류 거래를 실행할 수 있다
        processor.executeHeld(held.transactionId());
        assertThat(balanceOf(accA)).isEqualByComparingTo("0");
        assertThat(balanceOf(accB)).isEqualByComparingTo("900000");
        assertLedgerConsistent();
    }

    // ------------------------------------------------------------------
    // 시나리오 6: 실행·취소 동시 요청
    // ------------------------------------------------------------------

    @Test
    @DisplayName("시나리오 6 - 같은 보류 거래에 실행과 취소가 동시에 와도 하나만 성공한다")
    void concurrentExecuteAndCancel_onlyOneWins() throws Exception {
        int rounds = 12;
        int executed = 0;
        int cancelled = 0;

        for (int round = 0; round < rounds; round++) {
            fds.holdAll("VELOCITY");
            TransferResponse held = transferService.transfer(userId, transfer(accA, accB, "1000"));
            Long txId = held.transactionId();

            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            runConcurrently(2, i -> {
                try {
                    if (i == 0) {
                        processor.executeHeld(txId);
                    } else {
                        processor.cancelHeld(txId);
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });

            // 정확히 하나만 실패하고, 그 실패는 "이미 처리됨"이어야 한다
            assertThat(failures).singleElement().isInstanceOf(TransactionNotHeldException.class);
            TransactionStatus status = transactionRepository.findById(txId).orElseThrow().getStatus();
            assertThat(status).isIn(TransactionStatus.COMPLETED, TransactionStatus.CANCELLED);
            // 거래 상태와 홀드 상태가 같은 쪽으로 확정돼야 한다
            assertThat(holdOf(txId).getStatus()).isEqualTo(
                    status == TransactionStatus.COMPLETED ? AccountHold.Status.CONSUMED : AccountHold.Status.RELEASED);
            if (status == TransactionStatus.COMPLETED) {
                executed++;
            } else {
                cancelled++;
            }
        }

        // 실행된 건수만큼만 돈이 움직였다
        BigDecimal moved = new BigDecimal("1000").multiply(BigDecimal.valueOf(executed));
        assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL.subtract(moved));
        assertThat(balanceOf(accB)).isEqualByComparingTo(moved);
        assertThat(availableOf(accA)).isEqualByComparingTo(INITIAL.subtract(moved));
        assertThat(executed + cancelled).isEqualTo(rounds);
        assertLedgerConsistent();
    }

    @Test
    @DisplayName("이미 처리된 보류 거래는 다시 실행·취소할 수 없다")
    void resolvedTransaction_cannotBeResolvedAgain() {
        fds.holdAll("VELOCITY");
        Long executedId = transferService.transfer(userId, transfer(accA, accB, "1000")).transactionId();
        Long cancelledId = transferService.transfer(userId, transfer(accA, accB, "2000")).transactionId();
        processor.executeHeld(executedId);
        processor.cancelHeld(cancelledId);

        assertThatThrownBy(() -> processor.executeHeld(executedId)).isInstanceOf(TransactionNotHeldException.class);
        assertThatThrownBy(() -> processor.cancelHeld(executedId)).isInstanceOf(TransactionNotHeldException.class);
        assertThatThrownBy(() -> processor.executeHeld(cancelledId)).isInstanceOf(TransactionNotHeldException.class);
        assertThatThrownBy(() -> processor.cancelHeld(cancelledId)).isInstanceOf(TransactionNotHeldException.class);

        assertThat(balanceOf(accB)).isEqualByComparingTo("1000"); // 실행은 한 번만 반영됐다
        assertLedgerConsistent();
    }

    // ------------------------------------------------------------------
    // 판정과 확정 사이에 조건이 바뀐 경우
    // ------------------------------------------------------------------

    @Test
    @DisplayName("승인됐지만 그사이 입금 계좌가 해지되면 거래는 FAILED가 되고 묶인 금액이 풀린다")
    void approvedButAccountClosedMeanwhile_failsAndReleases() {
        // 판정이 진행되는 동안 입금 계좌(잔액 0)를 해지한다
        fds.duringEvaluation(request ->
                accountService.closeAccount(accB, userId, UUID.randomUUID().toString()));
        long ledgerBefore = ledgerEntryRepository.count();
        TransferRequest request = transfer(accA, accB, "300000");

        assertThatThrownBy(() -> transferService.transfer(userId, request))
                .isInstanceOf(InactiveAccountException.class);

        Transaction tx = transactionRepository.findByIdempotencyKey(request.idempotencyKey()).orElseThrow();
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.getFailureCode()).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat(tx.getCompletedAt()).isNull();
        assertThat(holdOf(tx.getId()).getStatus()).isEqualTo(AccountHold.Status.RELEASED);
        assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL);
        assertThat(availableOf(accA)).isEqualByComparingTo(INITIAL);
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerBefore);

        // 같은 키로 다시 요청하면 실패한 결과가 그대로 돌아온다 (다시 시도되지 않는다)
        fds.duringEvaluation(null);
        TransferResponse replay = transferService.transfer(userId, request);
        assertThat(replay.status()).isEqualTo(TransactionStatus.FAILED);
        assertThat(fds.requests()).hasSize(1);
    }

    @Test
    @DisplayName("보류 거래 실행이 재검증에 실패하면 거래는 HELD로 남고 금액도 계속 묶여 있다")
    void executeHeld_revalidationFailure_keepsHeld() {
        fds.holdAll("VELOCITY");
        Long txId = transferService.transfer(userId, transfer(accA, accB, "300000")).transactionId();
        accountService.closeAccount(accB, userId, UUID.randomUUID().toString());

        assertThatThrownBy(() -> processor.executeHeld(txId)).isInstanceOf(InactiveAccountException.class);

        assertThat(transactionRepository.findById(txId).orElseThrow().getStatus()).isEqualTo(TransactionStatus.HELD);
        assertThat(holdOf(txId).getStatus()).isEqualTo(AccountHold.Status.ACTIVE);
        assertThat(availableOf(accA)).isEqualByComparingTo("700000");

        // 실행은 못 해도 취소는 할 수 있다
        processor.cancelHeld(txId);
        assertThat(availableOf(accA)).isEqualByComparingTo(INITIAL);
        assertLedgerConsistent();
    }

    @Test
    @DisplayName("판정 도중 처리가 끊기면 거래는 PENDING_REVIEW, 홀드는 ACTIVE로 남는다 (돈은 움직이지 않는다)")
    void interruptedDuringEvaluation_leavesPendingReview() {
        fds.failWith(new IllegalStateException("판정 도중 프로세스 종료를 흉내 낸다"));
        TransferRequest request = transfer(accA, accB, "300000");
        long ledgerBefore = ledgerEntryRepository.count();

        assertThatThrownBy(() -> transferService.transfer(userId, request))
                .isInstanceOf(IllegalStateException.class);

        Transaction tx = transactionRepository.findByIdempotencyKey(request.idempotencyKey()).orElseThrow();
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING_REVIEW);
        assertThat(tx.getDecision()).isNull();
        assertThat(holdOf(tx.getId()).getStatus()).isEqualTo(AccountHold.Status.ACTIVE);
        assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL);
        assertThat(availableOf(accA)).isEqualByComparingTo("700000");
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerBefore);

        // 같은 키로 재요청하면 현재 상태(PENDING_REVIEW)를 돌려주고, 판정을 다시 부르지 않는다
        fds.approveAll();
        int callsBefore = fds.requests().size();
        TransferResponse replay = transferService.transfer(userId, request);
        assertThat(replay.status()).isEqualTo(TransactionStatus.PENDING_REVIEW);
        assertThat(fds.requests()).hasSize(callsBefore);
    }

    @Test
    @DisplayName("이미 확정된 거래에 판정을 다시 반영해도 아무것도 바뀌지 않는다 (재시도 안전)")
    void applyDecision_isIdempotent() {
        fds.approveAll();
        TransferResponse completed = transferService.transfer(userId, transfer(accA, accB, "300000"));
        long ledgerCount = ledgerEntryRepository.count();

        // 확정이 끝난 거래에 승인과 보류를 다시 적용해 본다
        processor.applyDecision(completed.transactionId(), Decision.approve(Source.FDS));
        processor.applyDecision(completed.transactionId(), Decision.hold(Source.FDS, List.of("LATE")));

        assertThat(transactionRepository.findById(completed.transactionId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.COMPLETED);
        assertThat(balanceOf(accA)).isEqualByComparingTo("700000");
        assertThat(balanceOf(accB)).isEqualByComparingTo("300000");
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerCount);
    }

    // ------------------------------------------------------------------
    // 멱등성
    // ------------------------------------------------------------------

    @Test
    @DisplayName("보류된 거래를 같은 키로 재요청하면 같은 보류 결과가 돌아오고 금액이 이중으로 묶이지 않는다")
    void replay_returnsCurrentState() {
        fds.holdAll("NEW_PAYEE_HIGH_VALUE");
        TransferRequest request = transfer(accA, accB, "300000");
        TransferResponse first = transferService.transfer(userId, request);

        TransferResponse replay = transferService.transfer(userId, request);

        assertThat(replay.transactionId()).isEqualTo(first.transactionId());
        assertThat(replay.status()).isEqualTo(TransactionStatus.HELD);
        assertThat(replay.reasonCodes()).containsExactly("NEW_PAYEE_HIGH_VALUE");
        assertThat(fds.requests()).hasSize(1);
        assertThat(holdRepository.count()).isEqualTo(1);
        assertThat(availableOf(accA)).isEqualByComparingTo("700000");

        // 실행된 뒤 같은 키로 다시 오면 완료 상태가 돌아온다
        processor.executeHeld(first.transactionId());
        assertThat(transferService.transfer(userId, request).status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(balanceOf(accB)).isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("같은 키를 다른 금액에 쓰면 409, 다른 사용자가 같은 키·같은 내용으로 요청하면 접근 거부")
    void replay_conflictAndOwnership() {
        TransferRequest request = transfer(accA, accB, "1000");
        transferService.transfer(userId, request);

        TransferRequest differentAmount = new TransferRequest(
                request.idempotencyKey(), accA, accB, new BigDecimal("2000"), "다른 금액");
        assertThatThrownBy(() -> transferService.transfer(userId, differentAmount))
                .isInstanceOf(IdempotencyKeyConflictException.class);

        // 키와 요청 내용을 모두 아는 다른 사용자라도 남의 거래 결과를 받아 갈 수 없다
        assertThatThrownBy(() -> transferService.transfer(otherUserId, request))
                .isInstanceOf(AccountAccessDeniedException.class);

        assertThat(balanceOf(accB)).isEqualByComparingTo("1000");
    }

    // ------------------------------------------------------------------
    // 동시성: 승인·보류가 섞인 이체 다발
    // ------------------------------------------------------------------

    @Test
    @DisplayName("승인·보류가 섞인 동시 이체 후 보류분을 동시에 실행·취소해도 불변식이 모두 유지된다")
    void concurrentMixedTransfers_keepInvariants() throws Exception {
        // 금액이 짝수 천 원이면 승인, 홀수 천 원이면 보류
        fds.decideBy(request -> request.amount().intValue() / 1000 % 2 == 0
                ? Decision.approve(Source.FDS)
                : Decision.hold(Source.FDS, List.of("ODD_AMOUNT")));

        int transfers = 60;
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        List<TransferResponse> responses = Collections.synchronizedList(new ArrayList<>());
        runConcurrently(transfers, i -> {
            try {
                // 1,000 ~ 10,000원. 총액 330,000원으로 잔액(1,000,000)에 여유가 있어 잔액 부족은 나지 않는다
                responses.add(transferService.transfer(userId, transfer(accA, accB, String.valueOf((i % 10 + 1) * 1000))));
            } catch (Throwable t) {
                failures.add(t);
            }
        });

        assertThat(failures).as("풀 크기 이하의 동시성에서는 실패가 없어야 한다").isEmpty();
        assertThat(responses).hasSize(transfers);
        List<TransferResponse> held = responses.stream()
                .filter(r -> r.status() == TransactionStatus.HELD).toList();
        List<TransferResponse> completed = responses.stream()
                .filter(r -> r.status() == TransactionStatus.COMPLETED).toList();
        assertThat(held).hasSize(30);
        assertThat(completed).hasSize(30);

        // 중간 점검: 완료분만 움직였고 보류분은 묶여 있다
        BigDecimal completedSum = sum(completed);
        BigDecimal heldSum = sum(held);
        assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL.subtract(completedSum));
        assertThat(availableOf(accA)).isEqualByComparingTo(INITIAL.subtract(completedSum).subtract(heldSum));
        assertLedgerConsistent();

        // 보류분을 동시에 처리한다: 짝수 번째는 실행, 홀수 번째는 취소
        List<Throwable> resolveFailures = Collections.synchronizedList(new ArrayList<>());
        runConcurrently(held.size(), i -> {
            try {
                if (i % 2 == 0) {
                    processor.executeHeld(held.get(i).transactionId());
                } else {
                    processor.cancelHeld(held.get(i).transactionId());
                }
            } catch (Throwable t) {
                resolveFailures.add(t);
            }
        });
        assertThat(resolveFailures).isEmpty();

        BigDecimal executedSum = BigDecimal.ZERO;
        for (int i = 0; i < held.size(); i += 2) {
            executedSum = executedSum.add(held.get(i).amount());
        }
        BigDecimal moved = completedSum.add(executedSum);
        assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL.subtract(moved));
        assertThat(balanceOf(accB)).isEqualByComparingTo(moved);
        // 남은 ACTIVE 홀드가 없으므로 가용잔액 == 원장잔액
        assertThat(availableOf(accA)).isEqualByComparingTo(INITIAL.subtract(moved));
        assertThat(countHolds(AccountHold.Status.ACTIVE)).isZero();
        assertThat(countHolds(AccountHold.Status.CONSUMED)).isEqualTo(45);
        assertThat(countHolds(AccountHold.Status.RELEASED)).isEqualTo(15);
        assertLedgerConsistent();
        assertHoldsMatchTransactions();
    }

    // ------------------------------------------------------------------
    // 헬퍼
    // ------------------------------------------------------------------

    private void assertLedgerConsistent() {
        assertThat(reconciliationService.findInconsistentAccounts()).as("계좌별 잔액 == 원장 합계").isEmpty();
        assertThat(reconciliationService.findUnbalancedJournals()).as("모든 분개의 합 0").isEmpty();
        assertThat(reconciliationService.trialBalance()).as("시산표 0").isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** ACTIVE 홀드는 PENDING_REVIEW/HELD 거래에만, 그리고 그런 거래에는 반드시 ACTIVE 홀드가 있어야 한다. */
    private void assertHoldsMatchTransactions() {
        Long mismatched = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM transactions t
                LEFT JOIN account_holds h ON h.transaction_id = t.id
                WHERE (t.status IN ('PENDING_REVIEW', 'HELD')) <> (h.status IS NOT DISTINCT FROM 'ACTIVE')
                """, Long.class);
        assertThat(mismatched).as("거래 상태와 홀드 상태의 짝").isZero();
    }

    private long countHolds(AccountHold.Status status) {
        return holdRepository.findAll().stream().filter(h -> h.getStatus() == status).count();
    }

    private static BigDecimal sum(List<TransferResponse> responses) {
        return responses.stream().map(TransferResponse::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Long saveUser(String loginId, String email) {
        return userRepository.save(User.builder()
                .loginId(loginId).password("x").name("2단계유저")
                .email(email).role(User.UserRole.ROLE_USER).build()).getId();
    }

    private String open(Long ownerId, BigDecimal initialBalance) {
        return accountService.openAccount(ownerId,
                new AccountOpenRequest(UUID.randomUUID().toString(), initialBalance)).accountNumber();
    }

    private TransferRequest transfer(String from, String to, String amount) {
        return new TransferRequest(UUID.randomUUID().toString(), from, to, new BigDecimal(amount), "2단계 테스트");
    }

    private WithdrawRequest withdraw(String accountNumber, String amount) {
        return new WithdrawRequest(accountNumber, new BigDecimal(amount), UUID.randomUUID().toString(), "2단계 테스트");
    }

    private AccountHold holdOf(Long transactionId) {
        return holdRepository.findByTransactionId(transactionId).orElseThrow();
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }

    /** 원장잔액 − ACTIVE 홀드 합계를 SQL 한 문장으로 읽는다(두 값이 같은 스냅샷에서 나온다). */
    private BigDecimal availableOf(String accountNumber) {
        return jdbcTemplate.queryForObject("""
                SELECT a.balance - COALESCE((SELECT SUM(h.amount) FROM account_holds h
                                             WHERE h.account_id = a.id AND h.status = 'ACTIVE'), 0)
                FROM accounts a WHERE a.account_number = ?
                """, BigDecimal.class, accountNumber);
    }

    private void runConcurrently(int tasks, java.util.function.IntConsumer task) throws InterruptedException {
        // 커넥션 풀(20)보다 적은 스레드로 돌려 풀 포화가 아니라 락 경합만 남긴다
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks, 10));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);
        for (int i = 0; i < tasks; i++) {
            int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    task.accept(index);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
    }
}
