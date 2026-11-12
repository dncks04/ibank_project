package com.ibank.internal;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.hold.entity.AccountHold;
import com.ibank.domain.hold.repository.AccountHoldRepository;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.ledger.service.ReconciliationService;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.entity.Transaction.TransactionStatus;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.global.audit.AuditLog;
import com.ibank.global.audit.AuditLogRepository;
import com.ibank.global.security.JwtProvider;
import com.ibank.internal.idempotency.InternalIdempotencyKeyRepository;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보류 거래 내부 API. 실제 시스템 토큰으로 보안 필터부터 DB까지 전 구간을 지난다.
 * 계약({@code docs/contracts/ibank-internal.openapi.yaml})의 응답 형식·오류 코드·멱등성 규칙을 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
class InternalTransactionApiTest {

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

    private static final String CLIENT_ID = "fds-service";
    private static final BigDecimal INITIAL = new BigDecimal("1000000");

    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private WebApplicationContext wac;
    @Autowired private ScriptedFraudDecisionPort fds;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private TransferService transferService;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountHoldRepository holdRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private InternalIdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private String systemToken;
    private Long userId;
    private String accA;
    private String accB;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        fds.reset();
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        systemToken = jwtProvider.generateSystemToken(CLIENT_ID, Duration.ofMinutes(5));

        userId = userRepository.save(User.builder()
                .loginId("internal-user").password("x").name("고객").email("internal@test.com")
                .birthDate(LocalDate.of(1950, 1, 1)).role(User.UserRole.ROLE_USER).build()).getId();
        accA = open(INITIAL);
        accB = open(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------------
    // 조회
    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET: 보류 거래의 상태·홀드·판정을 계약 형식으로 돌려준다")
    void get_heldTransaction() throws Exception {
        Long txId = hold("300000", "NEW_PAYEE_HIGH_VALUE", "DEPOSIT_CLOSED_THEN_TRANSFER");

        MvcResult result = mockMvc.perform(authorized(get("/internal/transactions/{id}", txId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transactionId").value(txId))
                .andExpect(jsonPath("$.data.type").value("TRANSFER"))
                .andExpect(jsonPath("$.data.channel").value("MOBILE"))
                .andExpect(jsonPath("$.data.status").value("HELD"))
                // 금액은 숫자가 아니라 십진 문자열
                .andExpect(jsonPath("$.data.amount").value("300000.00"))
                .andExpect(jsonPath("$.data.fromAccountNumber").value(accA))
                .andExpect(jsonPath("$.data.toAccountNumber").value(accB))
                .andExpect(jsonPath("$.data.customerId").value(userId))
                .andExpect(jsonPath("$.data.hold.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.hold.amount").value("300000.00"))
                .andExpect(jsonPath("$.data.decision.decision").value("HOLD"))
                .andExpect(jsonPath("$.data.decision.decisionSource").value("FDS"))
                .andExpect(jsonPath("$.data.decision.riskLevel").value("DANGER"))
                .andExpect(jsonPath("$.data.decision.reasonCodes[0]").value("NEW_PAYEE_HIGH_VALUE"))
                .andExpect(jsonPath("$.data.decision.reasonCodes[1]").value("DEPOSIT_CLOSED_THEN_TRANSFER"))
                .andExpect(jsonPath("$.data.completedAt").doesNotExist())
                .andReturn();

        // 시각은 영업 타임존(+09:00) 오프셋이 붙은 ISO-8601이고, 홀드 만료는 요청 24시간 뒤(기본 최대 보류 시간)
        String body = result.getResponse().getContentAsString();
        OffsetDateTime createdAt = OffsetDateTime.parse(com.jayway.jsonpath.JsonPath.read(body, "$.data.createdAt"));
        OffsetDateTime expiresAt = OffsetDateTime.parse(com.jayway.jsonpath.JsonPath.read(body, "$.data.hold.expiresAt"));
        assertThat(createdAt.getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
        assertThat(Duration.between(createdAt, expiresAt)).isBetween(Duration.ofHours(24).minusSeconds(5), Duration.ofHours(24).plusSeconds(5));
    }

    @Test
    @DisplayName("GET: 없는 거래는 404 TRANSACTION_NOT_FOUND")
    void get_notFound() throws Exception {
        mockMvc.perform(authorized(get("/internal/transactions/{id}", 999_999)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // 시나리오 2·3을 API로
    // ------------------------------------------------------------------

    @Test
    @DisplayName("execute: 보류 거래를 실행하면 COMPLETED, 홀드 CONSUMED, 분개가 생기고 감사 로그에 호출 서비스가 남는다")
    void execute_completes() throws Exception {
        Long txId = hold("300000", "NEW_PAYEE_HIGH_VALUE");

        mockMvc.perform(authorized(post("/internal/transactions/{id}/execute", txId))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"GUARDIAN_APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.hold.status").value("CONSUMED"))
                .andExpect(jsonPath("$.data.resolutionReasonCode").value("GUARDIAN_APPROVED"))
                .andExpect(jsonPath("$.data.completedAt").exists());

        assertThat(balanceOf(accA)).isEqualByComparingTo("700000");
        assertThat(balanceOf(accB)).isEqualByComparingTo("300000");
        assertThat(availableOf(accA)).isEqualByComparingTo("700000");
        assertLedgerConsistent();

        assertThat(audits("INTERNAL_TX_EXECUTE")).singleElement().satisfies(audit -> {
            assertThat(audit.getActor()).isEqualTo(CLIENT_ID);
            assertThat(audit.getTarget()).isEqualTo("transaction:" + txId);
            assertThat(audit.getResult()).isEqualTo("SUCCESS");
            assertThat(audit.getDetail()).isEqualTo("GUARDIAN_APPROVED");
        });
    }

    @Test
    @DisplayName("cancel: 보류 거래를 취소하면 CANCELLED, 홀드 RELEASED, 가용잔액이 원복되고 분개가 없다")
    void cancel_releases() throws Exception {
        Long txId = hold("300000", "NEW_PAYEE_HIGH_VALUE");
        long ledgerBefore = ledgerEntryRepository.count();

        // 본문 없이도 된다
        mockMvc.perform(authorized(post("/internal/transactions/{id}/cancel", txId))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.hold.status").value("RELEASED"))
                .andExpect(jsonPath("$.data.completedAt").doesNotExist());

        assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL);
        assertThat(availableOf(accA)).isEqualByComparingTo(INITIAL);
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerBefore);
        assertThat(audits("INTERNAL_TX_CANCEL")).singleElement()
                .satisfies(audit -> assertThat(audit.getActor()).isEqualTo(CLIENT_ID));
    }

    // ------------------------------------------------------------------
    // 멱등성
    // ------------------------------------------------------------------

    @Test
    @DisplayName("같은 키로 같은 요청을 다시 보내면 처음과 글자 그대로 같은 응답이 오고, 실행은 한 번뿐이다")
    void sameKeySameRequest_replaysStoredResponse() throws Exception {
        Long txId = hold("300000", "VELOCITY");
        String key = UUID.randomUUID().toString();

        String first = execute(txId, key, "GUARDIAN_APPROVED").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long ledgerAfterFirst = ledgerEntryRepository.count();

        String second = execute(txId, key, "GUARDIAN_APPROVED").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String third = execute(txId, key, "GUARDIAN_APPROVED").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerAfterFirst);
        assertThat(balanceOf(accB)).isEqualByComparingTo("300000"); // 한 번만 이동
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 키를 다른 요청(다른 동작·다른 사유·다른 거래)에 쓰면 409 IDEMPOTENCY_KEY_CONFLICT")
    void sameKeyDifferentRequest_conflict() throws Exception {
        Long txId = hold("300000", "VELOCITY");
        Long otherTxId = hold("100000", "VELOCITY");
        String key = UUID.randomUUID().toString();
        execute(txId, key, "GUARDIAN_APPROVED").andExpect(status().isOk());

        // 실행에 쓴 키로 취소 요청
        mockMvc.perform(authorized(post("/internal/transactions/{id}/cancel", txId)).header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        // 같은 동작이지만 사유가 다름
        execute(txId, key, "CUSTOMER_CONFIRMED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        // 같은 키를 다른 거래에
        execute(otherTxId, key, "GUARDIAN_APPROVED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        // 충돌로 거부된 요청은 아무것도 바꾸지 않았다
        assertThat(statusOf(txId)).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(statusOf(otherTxId)).isEqualTo(TransactionStatus.HELD);
    }

    @Test
    @DisplayName("이미 처리된 거래에 새 키로 요청하면 409 TRANSACTION_NOT_HELD")
    void newKeyOnResolvedTransaction_notHeld() throws Exception {
        Long txId = hold("300000", "VELOCITY");
        execute(txId, UUID.randomUUID().toString(), null).andExpect(status().isOk());

        execute(txId, UUID.randomUUID().toString(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_HELD"));
        mockMvc.perform(authorized(post("/internal/transactions/{id}/cancel", txId))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_HELD"));

        assertThat(statusOf(txId)).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(balanceOf(accB)).isEqualByComparingTo("300000");
    }

    @Test
    @DisplayName("같은 키의 같은 요청이 동시에 와도 실행은 한 번이고 모두 같은 성공 응답을 받는다")
    void sameKeyConcurrent_executesOnce() throws Exception {
        Long txId = hold("300000", "VELOCITY");
        String key = UUID.randomUUID().toString();
        long ledgerBefore = ledgerEntryRepository.count();

        List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        runConcurrently(6, i -> {
            MvcResult result = execute(txId, key, "GUARDIAN_APPROVED").andReturn();
            statuses.add(result.getResponse().getStatus());
            bodies.add(result.getResponse().getContentAsString());
        });

        assertThat(statuses).hasSize(6).containsOnly(200);
        assertThat(bodies.stream().distinct()).hasSize(1);
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerBefore + 2); // 분개 한 번(leg 2건)
        assertThat(balanceOf(accB)).isEqualByComparingTo("300000");
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);
        assertLedgerConsistent();
    }

    // ------------------------------------------------------------------
    // 시나리오 6: 실행·취소 동시 요청
    // ------------------------------------------------------------------

    @Test
    @DisplayName("시나리오 6 - 같은 거래에 실행과 취소가 동시에 오면 하나만 200, 다른 하나는 409 TRANSACTION_NOT_HELD")
    void concurrentExecuteAndCancel() throws Exception {
        int executed = 0;
        for (int round = 0; round < 8; round++) {
            Long txId = hold("1000", "VELOCITY");
            List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
            List<String> conflictBodies = Collections.synchronizedList(new ArrayList<>());

            runConcurrently(2, i -> {
                String action = i == 0 ? "execute" : "cancel";
                MvcResult result = mockMvc.perform(authorized(post("/internal/transactions/{id}/" + action, txId))
                        .header("Idempotency-Key", UUID.randomUUID().toString())).andReturn();
                statuses.add(result.getResponse().getStatus());
                if (result.getResponse().getStatus() == 409) {
                    conflictBodies.add(result.getResponse().getContentAsString());
                }
            });

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(conflictBodies).singleElement().asString().contains("TRANSACTION_NOT_HELD");
            TransactionStatus status = statusOf(txId);
            assertThat(status).isIn(TransactionStatus.COMPLETED, TransactionStatus.CANCELLED);
            assertThat(holdRepository.findByTransactionId(txId).orElseThrow().getStatus()).isEqualTo(
                    status == TransactionStatus.COMPLETED ? AccountHold.Status.CONSUMED : AccountHold.Status.RELEASED);
            if (status == TransactionStatus.COMPLETED) {
                executed++;
            }
        }
        // 실행된 건수만큼만 돈이 움직였다
        assertThat(balanceOf(accB)).isEqualByComparingTo(new BigDecimal("1000").multiply(BigDecimal.valueOf(executed)));
        assertLedgerConsistent();
    }

    // ------------------------------------------------------------------
    // 실행 재검증 실패
    // ------------------------------------------------------------------

    @Test
    @DisplayName("실행 재검증에 실패하면 422와 사유 코드, 거래는 HELD로 남고 멱등성 키도 저장되지 않는다")
    void execute_revalidationFails_staysHeld() throws Exception {
        Long txId = hold("300000", "VELOCITY");
        accountService.closeAccount(accB, userId, UUID.randomUUID().toString()); // 입금 계좌 해지
        String key = UUID.randomUUID().toString();

        execute(txId, key, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        assertThat(statusOf(txId)).isEqualTo(TransactionStatus.HELD);
        assertThat(holdRepository.findByTransactionId(txId).orElseThrow().getStatus()).isEqualTo(AccountHold.Status.ACTIVE);
        assertThat(availableOf(accA)).isEqualByComparingTo("700000");
        // 실패는 키를 소비하지 않는다: 같은 키로 다시 보내면 다시 평가된다(여전히 실패)
        assertThat(idempotencyKeyRepository.count()).isZero();
        execute(txId, key, null).andExpect(status().isUnprocessableEntity());
        // 실패 감사도 남는다
        assertThat(audits("INTERNAL_TX_EXECUTE")).hasSize(2).allSatisfy(audit -> {
            assertThat(audit.getResult()).isEqualTo("FAILURE");
            assertThat(audit.getActor()).isEqualTo(CLIENT_ID);
        });

        // 실행할 수 없는 거래는 취소로 정리한다
        mockMvc.perform(authorized(post("/internal/transactions/{id}/cancel", txId))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertThat(availableOf(accA)).isEqualByComparingTo(INITIAL);
    }

    // ------------------------------------------------------------------
    // 요청 형식·권한
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Idempotency-Key가 없거나 너무 길면 400 IDEMPOTENCY_KEY_REQUIRED, 사유 코드 형식이 틀리면 400 INVALID_REQUEST")
    void badRequests() throws Exception {
        Long txId = hold("300000", "VELOCITY");

        mockMvc.perform(authorized(post("/internal/transactions/{id}/execute", txId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mockMvc.perform(authorized(post("/internal/transactions/{id}/cancel", txId))
                        .header("Idempotency-Key", "k".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mockMvc.perform(authorized(post("/internal/transactions/{id}/execute", txId))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"lower case, with comma\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(authorized(post("/internal/transactions/{id}/execute", 999_999))
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));

        assertThat(statusOf(txId)).isEqualTo(TransactionStatus.HELD);
        assertThat(idempotencyKeyRepository.count()).isZero();
    }

    @Test
    @DisplayName("시스템 토큰이 없으면 401, 실행도 되지 않는다")
    void noToken_unauthorized() throws Exception {
        Long txId = hold("300000", "VELOCITY");

        mockMvc.perform(post("/internal/transactions/{id}/execute", txId)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(statusOf(txId)).isEqualTo(TransactionStatus.HELD);
    }

    // ------------------------------------------------------------------
    // 헬퍼
    // ------------------------------------------------------------------

    /** 판정이 HOLD인 이체를 만들어 보류 거래의 ID를 돌려준다. */
    private Long hold(String amount, String... reasonCodes) {
        fds.holdAll(reasonCodes);
        TransferResponse held = transferService.transfer(userId, new TransferRequest(
                UUID.randomUUID().toString(), accA, accB, new BigDecimal(amount), "내부 API 테스트"));
        assertThat(held.status()).isEqualTo(TransactionStatus.HELD);
        return held.transactionId();
    }

    private org.springframework.test.web.servlet.ResultActions execute(Long txId, String key, String reasonCode)
            throws Exception {
        MockHttpServletRequestBuilder request = authorized(post("/internal/transactions/{id}/execute", txId))
                .header("Idempotency-Key", key);
        if (reasonCode != null) {
            request.contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"" + reasonCode + "\"}");
        }
        return mockMvc.perform(request);
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + systemToken);
    }

    private String open(BigDecimal initialBalance) {
        return accountService.openAccount(userId,
                new AccountOpenRequest(UUID.randomUUID().toString(), initialBalance)).accountNumber();
    }

    private TransactionStatus statusOf(Long txId) {
        return transactionRepository.findById(txId).orElseThrow().getStatus();
    }

    private List<AuditLog> audits(String action) {
        return auditLogRepository.findAll().stream().filter(log -> action.equals(log.getAction())).toList();
    }

    private void assertLedgerConsistent() {
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();
        assertThat(reconciliationService.findUnbalancedJournals()).isEmpty();
        assertThat(reconciliationService.trialBalance()).isEqualByComparingTo(BigDecimal.ZERO);
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

    @FunctionalInterface
    private interface ThrowingTask {
        void run(int index) throws Exception;
    }

    private void runConcurrently(int tasks, ThrowingTask task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < tasks; i++) {
            int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    task.run(index);
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
        assertThat(failures).as("요청 자체가 예외로 끝나면 안 된다").isEmpty();
    }
}
