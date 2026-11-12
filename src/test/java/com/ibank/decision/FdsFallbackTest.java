package com.ibank.decision;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.decision.FallbackDecisionPolicy;
import com.ibank.domain.decision.FraudDecisionPort.Source;
import com.ibank.domain.decision.FraudDecisionPort.Verdict;
import com.ibank.domain.ledger.service.ReconciliationService;
import com.ibank.domain.transaction.dto.DepositRequest;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.entity.Transaction.TransactionStatus;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.global.audit.AuditLog;
import com.ibank.global.audit.AuditLogRepository;
import com.ibank.global.time.BusinessClock;
import com.ibank.support.DatabaseCleaner;
import com.ibank.support.StubFdsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시나리오 4 — FDS 서비스 장애 시 대체 정책.
 *
 * <p>ibank를 실제 HTTP 어댑터({@code ibank.fds.mode=http})로 띄우고 FDS 서비스 자리에 흉내 서버를 둔다.
 * 판정 서버가 타임아웃되면 65세 이상(또는 나이 미상) 고객의 거래는 보류, 그 외는 완료되고,
 * 어느 쪽이든 {@code decisionSource=FALLBACK}으로 기록되는지 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class FdsFallbackTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final StubFdsServer fds = new StubFdsServer();

    @DynamicPropertySource
    static void fdsProperties(DynamicPropertyRegistry registry) {
        registry.add("ibank.fds.mode", () -> "http");
        registry.add("ibank.fds.base-url", fds::baseUrl);
        registry.add("ibank.fds.response-timeout", () -> "500ms");
        // 이 테스트는 호출마다의 타임아웃과 대체 정책을 본다. 회로가 열려 호출이 생략되지 않도록 창을 크게 둔다.
        registry.add("ibank.fds.circuit-breaker.sliding-window-size", () -> "1000");
        registry.add("ibank.fds.circuit-breaker.minimum-calls", () -> "1000");
    }

    @AfterAll
    static void stopFds() {
        fds.close();
    }

    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private TransferService transferService;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private BusinessClock businessClock;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final BigDecimal INITIAL = new BigDecimal("1000000");
    private static final BigDecimal AMOUNT = new BigDecimal("300000");

    private String payee;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        fds.reset();
        Long payeeOwner = saveUser("payee", LocalDate.of(1990, 1, 1));
        payee = open(payeeOwner, BigDecimal.ZERO);
    }

    // ---- FDS가 정상일 때: 실제 HTTP로 판정을 받는다 ----

    @Test
    @DisplayName("FDS가 응답하면 그 판정을 따른다 (APPROVE는 완료, HOLD는 보류 + 사유 코드)")
    void fdsResponds_decisionFollowed() {
        Long customer = saveUser("normal", LocalDate.of(1950, 1, 1)); // 고령이어도 FDS가 승인하면 완료된다
        String account = open(customer, INITIAL);

        TransferResponse approved = transferService.transfer(customer, transfer(account, AMOUNT));
        assertThat(approved.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(decisionOf(approved).getSource()).isEqualTo(Source.FDS);
        assertThat(decisionOf(approved).getDecisionId()).isEqualTo("dec-approve");

        fds.respond(200, StubFdsServer.holdBody("NEW_PAYEE_HIGH_VALUE"));
        TransferResponse held = transferService.transfer(customer, transfer(account, AMOUNT));
        assertThat(held.status()).isEqualTo(TransactionStatus.HELD);
        assertThat(held.reasonCodes()).containsExactly("NEW_PAYEE_HIGH_VALUE");
        assertThat(decisionOf(held).getSource()).isEqualTo(Source.FDS);

        assertThat(fallbackAudits()).isEmpty();
    }

    // ---- 시나리오 4 ----

    @Test
    @DisplayName("시나리오 4 - 판정 서버 타임아웃: 65세 이상 고객의 이체는 HELD, decisionSource=FALLBACK")
    void timeout_elderCustomer_held() {
        Long elder = saveUser("elder", LocalDate.of(1950, 5, 5));
        String account = open(elder, INITIAL);
        fds.delay(5_000);

        long started = System.nanoTime();
        TransferResponse response = transferService.transfer(elder, transfer(account, AMOUNT));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.status()).isEqualTo(TransactionStatus.HELD);
        assertThat(response.reasonCodes()).containsExactly(FallbackDecisionPolicy.REASON_FDS_UNAVAILABLE);
        // 서버는 5초 뒤에 답하지만, 응답 타임아웃(500ms) 근처에서 대체 정책으로 넘어간다
        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));

        Transaction tx = transactionRepository.findById(response.transactionId()).orElseThrow();
        assertThat(tx.getDecision().getVerdict()).isEqualTo(Verdict.HOLD);
        assertThat(tx.getDecision().getSource()).isEqualTo(Source.FALLBACK);
        assertThat(tx.getDecision().getDecisionId()).isNull();

        // 돈은 움직이지 않았고 금액만 묶였다
        assertThat(balanceOf(account)).isEqualByComparingTo(INITIAL);
        assertThat(availableOf(account)).isEqualByComparingTo(INITIAL.subtract(AMOUNT));
        assertThat(balanceOf(payee)).isEqualByComparingTo("0");

        assertThat(fallbackAudits()).singleElement().satisfies(audit -> {
            assertThat(audit.getTarget()).isEqualTo("transaction:" + tx.getId());
            assertThat(audit.getDetail()).contains("decisionSource=FALLBACK").contains("decision=HOLD");
        });
    }

    @Test
    @DisplayName("시나리오 4 - 판정 서버 타임아웃: 그 외 고객의 이체는 COMPLETED, decisionSource=FALLBACK")
    void timeout_otherCustomer_completed() {
        Long young = saveUser("young", LocalDate.of(1990, 5, 5));
        String account = open(young, INITIAL);
        fds.delay(5_000);

        TransferResponse response = transferService.transfer(young, transfer(account, AMOUNT));

        assertThat(response.status()).isEqualTo(TransactionStatus.COMPLETED);
        Transaction tx = transactionRepository.findById(response.transactionId()).orElseThrow();
        assertThat(tx.getDecision().getVerdict()).isEqualTo(Verdict.APPROVE);
        assertThat(tx.getDecision().getSource()).isEqualTo(Source.FALLBACK);
        assertThat(balanceOf(account)).isEqualByComparingTo(INITIAL.subtract(AMOUNT));
        assertThat(balanceOf(payee)).isEqualByComparingTo(AMOUNT);
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();
        assertThat(reconciliationService.trialBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(fallbackAudits()).singleElement().satisfies(audit ->
                assertThat(audit.getDetail()).contains("decisionSource=FALLBACK").contains("decision=APPROVE"));
    }

    @Test
    @DisplayName("대체 정책의 나이 경계: 오늘 만 65세가 된 고객은 보류, 내일 65세가 되는 고객은 승인")
    void fallback_ageBoundary() {
        LocalDate today = businessClock.today();
        Long turned65Today = saveUser("just65", today.minusYears(65));
        Long turns65Tomorrow = saveUser("almost65", today.minusYears(65).plusDays(1));
        String accountA = open(turned65Today, INITIAL);
        String accountB = open(turns65Tomorrow, INITIAL);
        fds.respond(503, "{}"); // 타임아웃이 아니라 오류 응답이어도 같은 정책이 적용된다

        assertThat(transferService.transfer(turned65Today, transfer(accountA, AMOUNT)).status())
                .isEqualTo(TransactionStatus.HELD);
        assertThat(transferService.transfer(turns65Tomorrow, transfer(accountB, AMOUNT)).status())
                .isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    @DisplayName("생년월일이 없는 고객은 나이를 알 수 없으므로 보호 쪽(보류)으로 처리한다")
    void fallback_unknownAge_held() {
        Long legacy = saveUser("legacy", null);
        String account = open(legacy, INITIAL);
        fds.respond(500, "{}");

        TransferResponse response = transferService.transfer(legacy, transfer(account, AMOUNT));

        assertThat(response.status()).isEqualTo(TransactionStatus.HELD);
        assertThat(decisionOf(response).getSource()).isEqualTo(Source.FALLBACK);
    }

    @Test
    @DisplayName("출금에도 같은 대체 정책이 적용된다")
    void fallback_appliesToWithdrawal() {
        Long elder = saveUser("elderw", LocalDate.of(1948, 1, 1));
        String account = open(elder, INITIAL);
        fds.respond(500, "{}");

        TransferResponse response = transferService.withdraw(elder,
                new WithdrawRequest(account, AMOUNT, UUID.randomUUID().toString(), "창구 인출"));

        assertThat(response.status()).isEqualTo(TransactionStatus.HELD);
        assertThat(balanceOf(account)).isEqualByComparingTo(INITIAL);
    }

    // ---- 판정을 기다리는 동안 계좌가 잠겨 있지 않다 ----

    @Test
    @DisplayName("판정 서버가 응답하지 않는 동안에도 같은 계좌의 다른 거래는 막히지 않는다")
    void whileWaitingForFds_accountIsNotLocked() throws Exception {
        Long customer = saveUser("waiting", LocalDate.of(1990, 1, 1));
        String account = open(customer, INITIAL);
        fds.delay(5_000);

        // 이체 하나가 판정 응답을 기다리게 한다 (응답 타임아웃 500ms 동안)
        CompletableFuture<TransferResponse> waiting = CompletableFuture.supplyAsync(
                () -> transferService.transfer(customer, transfer(account, AMOUNT)));
        long deadline = System.currentTimeMillis() + 5_000;
        while (fds.received().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(fds.received()).as("이체가 판정 호출 단계에 들어갔다").hasSize(1);

        // 그 사이 같은 계좌에 입금한다. 계좌 행 락이 잡혀 있었다면 판정이 끝날 때까지 기다려야 한다.
        long started = System.nanoTime();
        transferService.depositForUser(customer,
                new DepositRequest(account, new BigDecimal("1000"), UUID.randomUUID().toString(), "대기 중 입금"));
        Duration depositTook = Duration.ofNanos(System.nanoTime() - started);

        assertThat(waiting).as("입금이 끝났을 때 이체는 아직 판정을 기다리는 중이다").isNotDone();
        assertThat(depositTook).isLessThan(Duration.ofMillis(400));

        assertThat(waiting.get(10, TimeUnit.SECONDS).status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(balanceOf(account)).isEqualByComparingTo(INITIAL.subtract(AMOUNT).add(new BigDecimal("1000")));
    }

    // ---- 헬퍼 ----

    private Long saveUser(String loginId, LocalDate birthDate) {
        return userRepository.save(User.builder()
                .loginId(loginId).password("x").name("고객")
                .email(loginId + "@fallback.test").birthDate(birthDate)
                .role(User.UserRole.ROLE_USER).build()).getId();
    }

    private String open(Long ownerId, BigDecimal initialBalance) {
        return accountService.openAccount(ownerId,
                new AccountOpenRequest(UUID.randomUUID().toString(), initialBalance)).accountNumber();
    }

    private TransferRequest transfer(String from, BigDecimal amount) {
        return new TransferRequest(UUID.randomUUID().toString(), from, payee, amount, "대체 정책 테스트");
    }

    private com.ibank.domain.transaction.entity.TransactionDecision decisionOf(TransferResponse response) {
        return transactionRepository.findById(response.transactionId()).orElseThrow().getDecision();
    }

    private List<AuditLog> fallbackAudits() {
        return auditLogRepository.findAll().stream()
                .filter(log -> "FDS_FALLBACK".equals(log.getAction()))
                .toList();
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
