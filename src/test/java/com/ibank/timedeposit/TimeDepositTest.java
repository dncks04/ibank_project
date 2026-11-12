package com.ibank.timedeposit;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.dto.AccountResponse;
import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.entity.InsufficientBalanceException;
import com.ibank.domain.account.exception.InactiveAccountException;
import com.ibank.domain.account.exception.TimeDepositRestrictedException;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.interest.repository.InterestAccrualRepository;
import com.ibank.domain.ledger.service.ReconciliationService;
import com.ibank.domain.timedeposit.TimeDepositDtos.OpenRequest;
import com.ibank.domain.timedeposit.TimeDepositDtos.TerminateRequest;
import com.ibank.domain.timedeposit.TimeDepositDtos.TerminationResponse;
import com.ibank.domain.timedeposit.TimeDepositDtos.TimeDepositResponse;
import com.ibank.domain.timedeposit.TimeDepositService;
import com.ibank.domain.transaction.dto.DepositRequest;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.entity.Transaction.TransactionStatus;
import com.ibank.domain.transaction.exception.IdempotencyKeyConflictException;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.global.time.BusinessClock;
import com.ibank.internal.event.InternalEventService;
import com.ibank.internal.event.InternalEventsResponse.Event;
import com.ibank.support.DatabaseCleaner;
import com.ibank.support.ScriptedFraudDecisionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * 정기예금 가입과 해지. 자금 이동이 원장 불변식을 지키는지, 정기예금 계좌가 직접 입출금을 막는지,
 * 그리고 "해지 → 입금 → 전액 이체"의 흐름이 이벤트로 순서대로 드러나는지(시나리오 8) 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TimeDepositTest {

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
    @Autowired private TimeDepositService timeDepositService;
    @Autowired private TransferService transferService;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private InterestAccrualRepository interestAccrualRepository;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private InternalEventService eventService;
    @Autowired private BusinessClock businessClock;
    @Autowired private JobLauncher jobLauncher;
    @Autowired @Qualifier("reconciliationJob") private Job reconciliationJob;
    @Autowired @Qualifier("interestAccrualJob") private Job interestAccrualJob;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final BigDecimal INITIAL = new BigDecimal("10000000");

    private Long userId;
    private Long otherUserId;
    private String demand;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        fds.reset();
        userId = saveUser("td-user");
        otherUserId = saveUser("td-other");
        demand = openDemand(userId, INITIAL);
    }

    // ------------------------------------------------------------------
    // 가입
    // ------------------------------------------------------------------

    @Test
    @DisplayName("가입: 입출금 계좌에서 정기예금으로 자금이 옮겨지고 약정(만기일·금리)이 고정된다")
    void open_movesFunds() {
        TimeDepositResponse td = timeDepositService.open(userId, openRequest("3000000", 12));

        assertThat(td.principal()).isEqualByComparingTo("3000000");
        assertThat(td.contractRate()).isEqualByComparingTo("0.035");
        assertThat(td.maturityDate()).isEqualTo(businessClock.today().plusMonths(12));
        assertThat(balanceOf(demand)).isEqualByComparingTo("7000000");
        assertThat(balanceOf(td.accountNumber())).isEqualByComparingTo("3000000");

        // 일반 계좌 조회에서 유형으로 구분된다
        List<AccountResponse> accounts = accountService.getMyAccounts(userId);
        assertThat(accounts).extracting(AccountResponse::accountType)
                .containsExactlyInAnyOrder(Account.AccountType.DEMAND, Account.AccountType.TIME_DEPOSIT);

        assertLedgerConsistent(); // 돈이 생기거나 사라지지 않았다
    }

    @Test
    @DisplayName("가입: 같은 멱등성 키로 다시 요청하면 새로 가입하지 않고 처음 만든 정기예금을 돌려준다")
    void open_idempotent() {
        OpenRequest request = openRequest("3000000", 12);
        TimeDepositResponse first = timeDepositService.open(userId, request);

        TimeDepositResponse second = timeDepositService.open(userId, request);

        assertThat(second.accountNumber()).isEqualTo(first.accountNumber());
        assertThat(balanceOf(demand)).isEqualByComparingTo("7000000"); // 한 번만 빠져나갔다
        assertThat(accountRepository.count()).isEqualTo(2);
        // 다른 사용자가 같은 키를 쓰면 남의 정기예금 정보가 나가지 않는다
        assertThatThrownBy(() -> timeDepositService.open(otherUserId, request))
                .isInstanceOf(AccountAccessDeniedException.class);
    }

    @Test
    @DisplayName("가입: 가용잔액을 넘거나, 남의 계좌거나, 기간이 범위를 벗어나면 거절되고 아무것도 바뀌지 않는다")
    void open_rejected() {
        assertThatThrownBy(() -> timeDepositService.open(userId, openRequest("10000001", 12)))
                .isInstanceOf(InsufficientBalanceException.class);
        assertThatThrownBy(() -> timeDepositService.open(otherUserId, openRequest("1000", 12)))
                .isInstanceOf(AccountAccessDeniedException.class);
        assertThatThrownBy(() -> timeDepositService.open(userId, openRequest("1000", 61)))
                .isInstanceOf(IllegalArgumentException.class);

        // 보류 중인 이체가 묶어 둔 돈으로는 가입할 수 없다
        String payee = openDemand(otherUserId, BigDecimal.ZERO);
        fds.holdAll("NEW_PAYEE_HIGH_VALUE");
        transferService.transfer(userId, new TransferRequest(
                UUID.randomUUID().toString(), demand, payee, new BigDecimal("9000000"), "보류될 이체"));
        assertThatThrownBy(() -> timeDepositService.open(userId, openRequest("1000001", 12)))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(balanceOf(demand)).isEqualByComparingTo(INITIAL);
        assertThat(accountRepository.findAllByOwnerId(userId)).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 직접 입출금 금지
    // ------------------------------------------------------------------

    @Test
    @DisplayName("정기예금 계좌로는 직접 출금·이체·입금할 수 없다")
    void timeDeposit_blocksDirectOperations() {
        String td = timeDepositService.open(userId, openRequest("3000000", 12)).accountNumber();

        assertThatThrownBy(() -> transferService.withdraw(userId,
                new WithdrawRequest(td, new BigDecimal("1000"), UUID.randomUUID().toString(), "직접 출금")))
                .isInstanceOf(TimeDepositRestrictedException.class);
        assertThatThrownBy(() -> transferService.transfer(userId, new TransferRequest(
                UUID.randomUUID().toString(), td, demand, new BigDecimal("1000"), "정기예금에서 이체")))
                .isInstanceOf(TimeDepositRestrictedException.class);
        assertThatThrownBy(() -> transferService.transfer(userId, new TransferRequest(
                UUID.randomUUID().toString(), demand, td, new BigDecimal("1000"), "정기예금으로 이체")))
                .isInstanceOf(TimeDepositRestrictedException.class);
        assertThatThrownBy(() -> transferService.depositForUser(userId,
                new DepositRequest(td, new BigDecimal("1000"), UUID.randomUUID().toString(), "직접 입금")))
                .isInstanceOf(TimeDepositRestrictedException.class);

        assertThat(balanceOf(td)).isEqualByComparingTo("3000000");
        assertThat(balanceOf(demand)).isEqualByComparingTo("7000000");
        // 거절된 요청은 금액을 묶지도 않는다
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account_holds", Long.class)).isZero();
    }

    // ------------------------------------------------------------------
    // 해지
    // ------------------------------------------------------------------

    @Test
    @DisplayName("중도해지: 원금과 중도해지 금리로 일할 계산한 이자가 입출금 계좌로 들어오고 정기예금은 닫힌다")
    void terminate_early() {
        String td = timeDepositService.open(userId, openRequest("3650000", 12)).accountNumber();
        backdateOpening(td, 100); // 100일 전에 가입한 것으로 만든다

        TerminationResponse result = timeDepositService.terminate(userId, td, terminateRequest());

        // 3,650,000 × 0.5% × 100일 / 365 = 5,000.00
        assertThat(result.early()).isTrue();
        assertThat(result.appliedRate()).isEqualByComparingTo("0.005");
        assertThat(result.principal()).isEqualByComparingTo("3650000");
        assertThat(result.interest()).isEqualByComparingTo("5000.00");
        assertThat(balanceOf(demand)).isEqualByComparingTo(INITIAL.add(new BigDecimal("5000")));
        assertThat(balanceOf(td)).isEqualByComparingTo("0");
        Account closed = accountRepository.findByAccountNumber(td).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(Account.AccountStatus.CLOSED);
        assertThat(closed.getPaidInterest()).isEqualByComparingTo("5000.00");
        assertLedgerConsistent(); // 이자는 은행 이자 비용 계정과 분개되어 시산표가 0으로 유지된다
    }

    @Test
    @DisplayName("만기해지: 만기일이 지났으면 약정 금리로 가입일부터 일할 계산한다")
    void terminate_atMaturity() {
        String td = timeDepositService.open(userId, openRequest("3650000", 12)).accountNumber();
        backdateOpening(td, 365);
        jdbcTemplate.update("UPDATE accounts SET maturity_date = ? WHERE account_number = ?",
                java.sql.Date.valueOf(businessClock.today()), td); // 오늘이 만기일

        TerminationResponse result = timeDepositService.terminate(userId, td, terminateRequest());

        // 3,650,000 × 3.5% × 365일 / 365 = 127,750.00
        assertThat(result.early()).isFalse();
        assertThat(result.appliedRate()).isEqualByComparingTo("0.035");
        assertThat(result.interest()).isEqualByComparingTo("127750.00");
        assertThat(balanceOf(demand)).isEqualByComparingTo(INITIAL.add(new BigDecimal("127750")));
        assertLedgerConsistent();
    }

    @Test
    @DisplayName("해지: 같은 키 재요청은 처음 결과를 돌려주고, 이미 해지된 정기예금을 다른 키로 다시 해지할 수 없다")
    void terminate_idempotent() {
        String td = timeDepositService.open(userId, openRequest("3650000", 12)).accountNumber();
        backdateOpening(td, 100);
        TerminateRequest request = terminateRequest();
        TerminationResponse first = timeDepositService.terminate(userId, td, request);

        TerminationResponse replay = timeDepositService.terminate(userId, td, request);

        assertThat(replay.transactionId()).isEqualTo(first.transactionId());
        assertThat(replay.interest()).isEqualByComparingTo(first.interest());
        assertThat(replay.early()).isTrue();
        assertThat(balanceOf(demand)).isEqualByComparingTo(INITIAL.add(new BigDecimal("5000"))); // 한 번만 지급

        assertThatThrownBy(() -> timeDepositService.terminate(userId, td, terminateRequest()))
                .isInstanceOf(InactiveAccountException.class);
        // 같은 키를 다른 수령 계좌에 쓰면 충돌
        String another = openDemand(userId, BigDecimal.ZERO);
        assertThatThrownBy(() -> timeDepositService.terminate(userId, td,
                new TerminateRequest(request.idempotencyKey(), another)))
                .isInstanceOf(IdempotencyKeyConflictException.class);
        assertLedgerConsistent();
    }

    @Test
    @DisplayName("해지: 남의 정기예금, 남의 수령 계좌, 정기예금이 아닌 계좌는 거절된다")
    void terminate_rejected() {
        String td = timeDepositService.open(userId, openRequest("1000000", 12)).accountNumber();
        String othersDemand = openDemand(otherUserId, BigDecimal.ZERO);

        assertThatThrownBy(() -> timeDepositService.terminate(otherUserId, td,
                new TerminateRequest(UUID.randomUUID().toString(), othersDemand)))
                .isInstanceOf(AccountAccessDeniedException.class);
        assertThatThrownBy(() -> timeDepositService.terminate(userId, td,
                new TerminateRequest(UUID.randomUUID().toString(), othersDemand)))
                .isInstanceOf(AccountAccessDeniedException.class);
        String secondDemand = openDemand(userId, new BigDecimal("1000"));
        assertThatThrownBy(() -> timeDepositService.terminate(userId, secondDemand,
                new TerminateRequest(UUID.randomUUID().toString(), demand)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(balanceOf(td)).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("해지 요청이 동시에 여러 번 와도 한 번만 지급된다")
    void terminate_concurrent() throws Exception {
        String td = timeDepositService.open(userId, openRequest("3650000", 12)).accountNumber();
        backdateOpening(td, 100);

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<TerminationResponse> successes = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    successes.add(timeDepositService.terminate(userId, td, terminateRequest())); // 스레드마다 다른 키
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(threads - 1).allMatch(t -> t instanceof InactiveAccountException);
        assertThat(balanceOf(demand)).isEqualByComparingTo(INITIAL.add(new BigDecimal("5000")));
        assertLedgerConsistent();
    }

    // ------------------------------------------------------------------
    // 배치와의 관계
    // ------------------------------------------------------------------

    @Test
    @DisplayName("정산 배치는 계좌 유형과 무관하게 통과하고, 일일 이자 적립은 정기예금을 건너뛴다")
    void batches_withTimeDeposits() throws Exception {
        String td = timeDepositService.open(userId, openRequest("3650000", 12)).accountNumber();
        String tdToTerminate = timeDepositService.open(userId, openRequest("1000000", 6)).accountNumber();
        backdateOpening(tdToTerminate, 30);
        timeDepositService.terminate(userId, tdToTerminate, terminateRequest());

        var reconciliation = jobLauncher.run(reconciliationJob,
                new JobParametersBuilder().addLong("runAt", System.nanoTime()).toJobParameters());
        assertThat(reconciliation.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_results WHERE consistent = false", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reconciliation_results", Long.class))
                .isEqualTo(3); // 입출금 1 + 정기예금 2 (해지된 계좌 포함)

        var accrual = jobLauncher.run(interestAccrualJob, new JobParametersBuilder()
                .addString("accrualDate", "2026-06-01").addLong("runAt", System.nanoTime()).toJobParameters());
        assertThat(accrual.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Long tdId = accountRepository.findByAccountNumber(td).orElseThrow().getId();
        Long demandId = accountRepository.findByAccountNumber(demand).orElseThrow().getId();
        assertThat(interestAccrualRepository.findByAccountIdAndPaidFalse(tdId)).isEmpty();
        assertThat(interestAccrualRepository.findByAccountIdAndPaidFalse(demandId)).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 시나리오 8
    // ------------------------------------------------------------------

    @Test
    @DisplayName("시나리오 8 - 정기예금 중도해지 → 입출금 계좌 입금 → 전액 이체 요청이 이벤트로 순서대로 조회된다")
    void scenario8_terminationThenFullTransfer_eventsInOrder() {
        String td = timeDepositService.open(userId, openRequest("9000000", 12)).accountNumber();
        backdateOpening(td, 73);
        String payee = openDemand(otherUserId, BigDecimal.ZERO);
        long cursor = drain(0);

        // 1) 중도해지: 9,000,000 × 0.5% × 73 / 365 = 9,000.00 이자
        TerminationResponse termination = timeDepositService.terminate(userId, td, terminateRequest());
        BigDecimal balanceAfterTermination = balanceOf(demand);
        assertThat(balanceAfterTermination).isEqualByComparingTo("10009000");

        // 2) 직후 한도(단건 1,000만 원) 안에서 낼 수 있는 최대 금액을 낯선 계좌로 이체 — FDS가 보류한다
        fds.holdAll("DEPOSIT_CLOSED_THEN_TRANSFER", "NEW_PAYEE_HIGH_VALUE");
        TransferResponse transfer = transferService.transfer(userId, new TransferRequest(
                UUID.randomUUID().toString(), demand, payee, new BigDecimal("10000000"), "전액 이체"));
        assertThat(transfer.status()).isEqualTo(TransactionStatus.HELD);

        List<Event> events = eventService.read(cursor, 100).events();
        assertThat(events).extracting(Event::eventType).containsExactly(
                "TIME_DEPOSIT_TERMINATED", "TRANSACTION_REQUESTED", "TRANSACTION_HELD");
        assertThat(events).extracting(Event::id).isSorted();

        // 해지 이벤트만으로 "얼마가 어느 계좌로 들어갔는지"를 알 수 있다
        Event terminated = events.get(0);
        assertThat(terminated.aggregateId()).isEqualTo(td);
        assertThat(terminated.payload().get("accountNumber").asString()).isEqualTo(td);
        assertThat(terminated.payload().get("payoutAccountNumber").asString()).isEqualTo(demand);
        assertThat(terminated.payload().get("customerId").asLong()).isEqualTo(userId);
        assertThat(terminated.payload().get("principal").asString()).isEqualTo("9000000.00");
        assertThat(terminated.payload().get("interest").asString()).isEqualTo("9000.00");
        assertThat(terminated.payload().get("payoutAmount").asString()).isEqualTo("9009000.00");
        assertThat(terminated.payload().get("early").asBoolean()).isTrue();
        assertThat(terminated.payload().get("transactionId").asLong()).isEqualTo(termination.transactionId());

        // 뒤따르는 이체 이벤트는 같은 입출금 계좌에서 나간다 → FDS 서비스가 두 사건을 계좌로 잇는다
        Event requested = events.get(1);
        assertThat(requested.payload().get("fromAccountNumber").asString()).isEqualTo(demand);
        assertThat(requested.payload().get("amount").asString()).isEqualTo("10000000.00");
        assertThat(events.get(2).payload().get("decision").get("reasonCodes").get(0).asString())
                .isEqualTo("DEPOSIT_CLOSED_THEN_TRANSFER");

        // 보류된 동안 돈은 그대로 있다
        assertThat(balanceOf(demand)).isEqualByComparingTo(balanceAfterTermination);
        assertLedgerConsistent();
    }

    // ---- 헬퍼 ----

    private void assertLedgerConsistent() {
        assertThat(reconciliationService.findInconsistentAccounts()).as("계좌별 잔액 == 원장 합계").isEmpty();
        assertThat(reconciliationService.findUnbalancedJournals()).as("모든 분개의 합 0").isEmpty();
        assertThat(reconciliationService.trialBalance()).as("시산표 0").isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** 가입일을 n일 전으로 돌린다 (이자 일할 계산의 경과일수를 만들기 위함). */
    private void backdateOpening(String accountNumber, int days) {
        jdbcTemplate.update("UPDATE accounts SET created_at = created_at - (? * INTERVAL '1 day') WHERE account_number = ?",
                days, accountNumber);
    }

    private long drain(long after) {
        long cursor = after;
        var page = eventService.read(cursor, 500);
        while (!page.events().isEmpty()) {
            cursor = page.nextAfter();
            page = eventService.read(cursor, 500);
        }
        return cursor;
    }

    private Long saveUser(String loginId) {
        return userRepository.save(User.builder()
                .loginId(loginId).password("x").name("예금주").email(loginId + "@td.test")
                .birthDate(LocalDate.of(1950, 1, 1)).role(User.UserRole.ROLE_USER).build()).getId();
    }

    private String openDemand(Long ownerId, BigDecimal balance) {
        return accountService.openAccount(ownerId,
                new AccountOpenRequest(UUID.randomUUID().toString(), balance)).accountNumber();
    }

    private OpenRequest openRequest(String amount, int termMonths) {
        return new OpenRequest(UUID.randomUUID().toString(), demand, new BigDecimal(amount), termMonths);
    }

    private TerminateRequest terminateRequest() {
        return new TerminateRequest(UUID.randomUUID().toString(), demand);
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }
}
