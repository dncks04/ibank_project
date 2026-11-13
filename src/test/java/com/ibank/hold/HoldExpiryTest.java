package com.ibank.hold;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.hold.entity.AccountHold;
import com.ibank.domain.hold.repository.AccountHoldRepository;
import com.ibank.domain.hold.service.HoldExpiryProcessor;
import com.ibank.domain.hold.service.HoldExpiryService;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.ledger.service.ReconciliationService;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.entity.Transaction.TransactionStatus;
import com.ibank.domain.transaction.exception.DailyLimitExceededException;
import com.ibank.domain.transaction.exception.TransactionNotHeldException;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransactionProcessor;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.internal.event.InternalEventService;
import com.ibank.internal.event.InternalEventsResponse.Event;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 홀드 만료 안전장치. 최대 보류 시간을 넘긴 홀드가 풀리고 거래가 종료되는지,
 * 그리고 만료가 실행·취소와 겹쳐도 한 번만 적용되는지 확인한다.
 * 한도를 낮춰(단건 10,000 / 1일 20,000) 만료가 한도 사용량도 돌려주는지 함께 본다.
 */
@SpringBootTest(properties = {
        "ibank.limits.per-transaction-max=10000",
        "ibank.limits.daily-outflow-max=20000"
})
@Testcontainers(disabledWithoutDocker = true)
class HoldExpiryTest {

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
    @Autowired private HoldExpiryService expiryService;
    @Autowired private TransferService transferService;
    @Autowired private TransactionProcessor processor;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountHoldRepository holdRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private InternalEventService eventService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final BigDecimal INITIAL = new BigDecimal("100000");

    private Long userId;
    private String accA;
    private String accB;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        fds.reset();
        userId = userRepository.save(User.builder()
                .loginId("expiry-user").password("x").name("만료유저").email("expiry@test.com")
                .birthDate(LocalDate.of(1950, 1, 1)).role(User.UserRole.ROLE_USER).build()).getId();
        accA = open(INITIAL);
        accB = open(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("시나리오 7 - 최대 보류 시간이 지나면 거래는 EXPIRED, 홀드는 EXPIRED, 가용잔액이 원복되고 분개는 없다")
    void overdueHold_expires() {
        Long txId = hold("10000");
        long ledgerBefore = ledgerEntryRepository.count();
        long cursor = eventService.read(0, 100).nextAfter();
        assertThat(availableOf(accA)).isEqualByComparingTo("90000");

        // 아직 만료 전이면 아무 일도 일어나지 않는다
        assertThat(expiryService.expireOverdue()).isZero();
        assertThat(statusOf(txId)).isEqualTo(TransactionStatus.HELD);

        makeOverdue(txId);
        assertThat(expiryService.expireOverdue()).isEqualTo(1);

        Transaction tx = transactionRepository.findById(txId).orElseThrow();
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.EXPIRED);
        assertThat(tx.getCompletedAt()).isNull();
        assertThat(holdStatusOf(txId)).isEqualTo(AccountHold.Status.EXPIRED);
        assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL);
        assertThat(availableOf(accA)).isEqualByComparingTo(INITIAL);
        assertThat(balanceOf(accB)).isEqualByComparingTo("0");
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerBefore);
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();

        // FDS 서비스가 알 수 있도록 이벤트가 남는다
        List<Event> events = eventService.read(cursor, 100).events();
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("TRANSACTION_EXPIRED");
            assertThat(event.payload().get("status").asString()).isEqualTo("EXPIRED");
            assertThat(event.payload().get("reasonCode").asString()).isEqualTo(HoldExpiryProcessor.REASON_HOLD_EXPIRED);
        });

        // 다시 돌려도 더 할 일이 없다
        assertThat(expiryService.expireOverdue()).isZero();
    }

    @Test
    @DisplayName("만료된 거래는 더 이상 실행·취소할 수 없다")
    void expiredTransaction_cannotBeResolved() {
        Long txId = hold("10000");
        makeOverdue(txId);
        expiryService.expireOverdue();

        assertThatThrownBy(() -> processor.executeHeld(txId)).isInstanceOf(TransactionNotHeldException.class);
        assertThatThrownBy(() -> processor.cancelHeld(txId)).isInstanceOf(TransactionNotHeldException.class);
        assertThat(balanceOf(accB)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("홀드가 만료되면 그 금액은 1일 한도 사용량에서 즉시 빠진다")
    void expiry_freesDailyLimit() {
        Long first = hold("10000");
        hold("10000"); // 묶인 금액 20,000 == 1일 한도
        fds.approveAll();
        assertThatThrownBy(() -> transferService.transfer(userId, transfer("1")))
                .isInstanceOf(DailyLimitExceededException.class);

        makeOverdue(first);
        assertThat(expiryService.expireOverdue()).isEqualTo(1);

        // 만료 직후 같은 금액을 바로 다시 쓸 수 있고, 그 뒤에는 다시 한도가 찬다
        assertThat(transferService.transfer(userId, transfer("10000")).status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThatThrownBy(() -> transferService.transfer(userId, transfer("1")))
                .isInstanceOf(DailyLimitExceededException.class);
    }

    @Test
    @DisplayName("만료된 것만 풀고 나머지 보류는 그대로 둔다 (출금 보류도 같은 방식으로 만료된다)")
    void onlyOverdueHolds_expire() {
        Long overdueTransfer = hold("5000");
        Long freshTransfer = hold("3000");
        fds.holdAll("LARGE_CASH_WITHDRAWAL");
        Long overdueWithdrawal = transferService.withdraw(userId,
                new WithdrawRequest(accA, new BigDecimal("2000"), UUID.randomUUID().toString(), "출금")).transactionId();
        makeOverdue(overdueTransfer);
        makeOverdue(overdueWithdrawal);

        assertThat(expiryService.expireOverdue()).isEqualTo(2);

        assertThat(statusOf(overdueTransfer)).isEqualTo(TransactionStatus.EXPIRED);
        assertThat(statusOf(overdueWithdrawal)).isEqualTo(TransactionStatus.EXPIRED);
        assertThat(statusOf(freshTransfer)).isEqualTo(TransactionStatus.HELD);
        assertThat(holdStatusOf(freshTransfer)).isEqualTo(AccountHold.Status.ACTIVE);
        assertThat(availableOf(accA)).isEqualByComparingTo("97000"); // 남은 보류 3,000만 묶여 있다
    }

    @Test
    @DisplayName("판정 대기(PENDING_REVIEW)로 남은 거래의 홀드가 만료되면 FAILED로 확정하고 금액을 푼다")
    void overduePendingReview_fails() {
        fds.failWith(new IllegalStateException("판정 도중 중단"));
        TransferRequest request = transfer("10000");
        assertThatThrownBy(() -> transferService.transfer(userId, request)).isInstanceOf(IllegalStateException.class);
        Long txId = transactionRepository.findByIdempotencyKey(request.idempotencyKey()).orElseThrow().getId();
        assertThat(statusOf(txId)).isEqualTo(TransactionStatus.PENDING_REVIEW);
        makeOverdue(txId);

        assertThat(expiryService.expireOverdue()).isEqualTo(1);

        Transaction tx = transactionRepository.findById(txId).orElseThrow();
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.getFailureCode()).isEqualTo(HoldExpiryProcessor.REASON_HOLD_EXPIRED);
        assertThat(holdStatusOf(txId)).isEqualTo(AccountHold.Status.EXPIRED);
        assertThat(availableOf(accA)).isEqualByComparingTo(INITIAL);
    }

    @Test
    @DisplayName("만료와 실행이 같은 거래에 동시에 와도 하나만 적용된다 (돈이 나가면서 만료되는 일은 없다)")
    void concurrentExpiryAndExecute_onlyOneApplies() throws Exception {
        int executed = 0;
        int expired = 0;
        for (int round = 0; round < 10; round++) {
            Long txId = hold("1000");
            makeOverdue(txId);

            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            pool.submit(() -> run(start, done, failures, () -> processor.executeHeld(txId)));
            pool.submit(() -> run(start, done, failures, expiryService::expireOverdue));
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            pool.shutdown();

            TransactionStatus status = statusOf(txId);
            AccountHold.Status holdStatus = holdStatusOf(txId);
            if (status == TransactionStatus.COMPLETED) {
                // 실행이 이겼다: 홀드는 소비됐고 만료는 물러났다
                assertThat(holdStatus).isEqualTo(AccountHold.Status.CONSUMED);
                assertThat(failures).isEmpty();
                executed++;
            } else {
                // 만료가 이겼다: 실행은 "보류 상태가 아님"으로 거절됐다
                assertThat(status).isEqualTo(TransactionStatus.EXPIRED);
                assertThat(holdStatus).isEqualTo(AccountHold.Status.EXPIRED);
                assertThat(failures).singleElement().isInstanceOf(TransactionNotHeldException.class);
                expired++;
            }
        }

        // 실행된 건수만큼만 돈이 움직였다
        BigDecimal moved = new BigDecimal("1000").multiply(BigDecimal.valueOf(executed));
        assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL.subtract(moved));
        assertThat(balanceOf(accB)).isEqualByComparingTo(moved);
        assertThat(availableOf(accA)).isEqualByComparingTo(INITIAL.subtract(moved));
        assertThat(executed + expired).isEqualTo(10);
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();
        assertThat(reconciliationService.findUnbalancedJournals()).isEmpty();
        assertThat(reconciliationService.trialBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---- 헬퍼 ----

    private void run(CountDownLatch start, CountDownLatch done, List<Throwable> failures, Runnable action) {
        try {
            start.await();
            action.run();
        } catch (Throwable t) {
            failures.add(t);
        } finally {
            done.countDown();
        }
    }

    /** 판정이 HOLD인 이체를 만들어 보류 거래의 ID를 돌려준다. */
    private Long hold(String amount) {
        fds.holdAll("NEW_PAYEE_HIGH_VALUE");
        TransferResponse held = transferService.transfer(userId, transfer(amount));
        assertThat(held.status()).isEqualTo(TransactionStatus.HELD);
        return held.transactionId();
    }

    /** 홀드의 만료 시각을 과거로 돌려 최대 보류 시간이 지난 것으로 만든다. */
    private void makeOverdue(Long transactionId) {
        jdbcTemplate.update(
                "UPDATE account_holds SET expires_at = expires_at - INTERVAL '25 hours' WHERE transaction_id = ?",
                transactionId);
    }

    private String open(BigDecimal balance) {
        return accountService.openAccount(userId,
                new AccountOpenRequest(UUID.randomUUID().toString(), balance)).accountNumber();
    }

    private TransferRequest transfer(String amount) {
        return new TransferRequest(UUID.randomUUID().toString(), accA, accB, new BigDecimal(amount), "만료 테스트");
    }

    private TransactionStatus statusOf(Long txId) {
        return transactionRepository.findById(txId).orElseThrow().getStatus();
    }

    private AccountHold.Status holdStatusOf(Long txId) {
        return holdRepository.findByTransactionId(txId).orElseThrow().getStatus();
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }

    private BigDecimal availableOf(String accountNumber) {
        return jdbcTemplate.queryForObject("""
                SELECT a.balance - COALESCE((SELECT SUM(h.amount) FROM account_holds h
                                             WHERE h.account_id = a.id AND h.status = 'ACTIVE'), 0)
                FROM accounts a WHERE a.account_number = ?
                """, BigDecimal.class, accountNumber);
    }
}
