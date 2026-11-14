package com.ibank.internal;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.exception.AccountFrozenException;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.hold.entity.AccountHold;
import com.ibank.domain.hold.repository.AccountHoldRepository;
import com.ibank.domain.ledger.service.ReconciliationService;
import com.ibank.domain.timedeposit.TimeDepositDtos;
import com.ibank.domain.timedeposit.TimeDepositService;
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
import com.ibank.global.audit.AuditLogRepository;
import com.ibank.global.security.JwtProvider;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계좌 지급정지. 정지된 계좌에서는 돈이 나가지 않고 들어오는 것은 허용되는지,
 * 정지 시점에 묶여 있던 금액과 보류 거래가 어떻게 되는지, 그리고 내부 API의 멱등성을 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
class InternalAccountFreezeTest {

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
    @Autowired private WebApplicationContext wac;
    @Autowired private ScriptedFraudDecisionPort fds;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private TransferService transferService;
    @Autowired private TimeDepositService timeDepositService;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountHoldRepository holdRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private InternalEventService eventService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final BigDecimal INITIAL = new BigDecimal("1000000");

    private MockMvc mockMvc;
    private String token;
    private Long userId;
    private String accA;
    private String accB;
    private long cursor;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        fds.reset();
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        token = jwtProvider.generateSystemToken("fds-service", Duration.ofMinutes(5));
        userId = userRepository.save(User.builder()
                .loginId("freeze-user").password("x").name("정지유저").email("freeze@test.com")
                .birthDate(LocalDate.of(1950, 1, 1)).role(User.UserRole.ROLE_USER).build()).getId();
        accA = open(INITIAL);
        accB = open(new BigDecimal("50000"));
        cursor = eventService.read(0, 500).nextAfter();
    }

    @Test
    @DisplayName("지급정지된 계좌에서는 출금·이체가 나가지 않고, 입금과 받는 이체는 허용된다")
    void frozenAccount_blocksOutflowAllowsInflow() throws Exception {
        freeze(accA, UUID.randomUUID().toString(), "SUSPECTED_SCAM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountNumber").value(accA))
                .andExpect(jsonPath("$.data.status").value("FROZEN"))
                .andExpect(jsonPath("$.data.reasonCode").value("SUSPECTED_SCAM"));

        // 나가는 돈은 모두 막힌다
        assertThatThrownBy(() -> transferService.withdraw(userId,
                new WithdrawRequest(accA, new BigDecimal("1000"), UUID.randomUUID().toString(), "출금")))
                .isInstanceOf(AccountFrozenException.class);
        assertThatThrownBy(() -> transferService.transfer(userId, transfer(accA, accB, "1000")))
                .isInstanceOf(AccountFrozenException.class);
        assertThatThrownBy(() -> timeDepositService.open(userId,
                new TimeDepositDtos.OpenRequest(UUID.randomUUID().toString(), accA, new BigDecimal("1000"), 12)))
                .isInstanceOf(AccountFrozenException.class);
        assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL);
        assertThat(holdRepository.count()).as("거절된 요청은 금액을 묶지도 않는다").isZero();

        // 들어오는 돈은 받는다
        transferService.depositForUser(userId,
                new DepositRequest(accA, new BigDecimal("3000"), UUID.randomUUID().toString(), "입금"));
        TransferResponse incoming = transferService.transfer(userId, transfer(accB, accA, "2000"));
        assertThat(incoming.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(balanceOf(accA)).isEqualByComparingTo("1005000");

        // 상태와 사유가 계좌에 남고, 고객 조회에도 정지 상태가 보인다
        Account frozen = accountRepository.findByAccountNumber(accA).orElseThrow();
        assertThat(frozen.getStatus()).isEqualTo(Account.AccountStatus.FROZEN);
        assertThat(frozen.getFrozenReason()).isEqualTo("SUSPECTED_SCAM");
        assertThat(accountService.getAccount(accA, userId).status()).isEqualTo(Account.AccountStatus.FROZEN);

        // 이벤트와 감사 로그
        List<Event> events = eventService.read(cursor, 100).events();
        assertThat(events.get(0).eventType()).isEqualTo("ACCOUNT_FROZEN");
        assertThat(events.get(0).aggregateId()).isEqualTo(accA);
        assertThat(events.get(0).payload().get("reasonCode").asString()).isEqualTo("SUSPECTED_SCAM");
        assertThat(events.get(0).payload().get("customerId").asLong()).isEqualTo(userId);
        assertThat(auditLogRepository.findAll().stream()
                .filter(log -> "INTERNAL_ACCOUNT_FREEZE".equals(log.getAction()))).singleElement().satisfies(audit -> {
            assertThat(audit.getActor()).isEqualTo("fds-service");
            assertThat(audit.getTarget()).isEqualTo(accA);
            assertThat(audit.getDetail()).isEqualTo("SUSPECTED_SCAM");
        });
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();
    }

    @Test
    @DisplayName("해제하면 다시 출금할 수 있고 ACCOUNT_UNFROZEN 이벤트가 남는다")
    void unfreeze_restoresOutflow() throws Exception {
        freeze(accA, UUID.randomUUID().toString(), "SUSPECTED_SCAM").andExpect(status().isOk());

        unfreeze(accA, UUID.randomUUID().toString(), "CUSTOMER_VERIFIED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        transferService.withdraw(userId,
                new WithdrawRequest(accA, new BigDecimal("1000"), UUID.randomUUID().toString(), "출금"));
        assertThat(balanceOf(accA)).isEqualByComparingTo("999000");
        assertThat(accountRepository.findByAccountNumber(accA).orElseThrow().getFrozenReason()).isNull();
        assertThat(eventService.read(cursor, 100).events()).extracting(Event::eventType)
                .startsWith("ACCOUNT_FROZEN", "ACCOUNT_UNFROZEN");
    }

    @Test
    @DisplayName("멱등성: 같은 키 재전송은 같은 응답, 이미 정지된 계좌의 재정지는 성공이지만 이벤트를 다시 내지 않는다")
    void freeze_idempotent() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = freeze(accA, key, "SUSPECTED_SCAM").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String replay = freeze(accA, key, "SUSPECTED_SCAM").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);

        // 다른 키로 다시 정지: 상태는 이미 FROZEN이므로 그대로 성공
        freeze(accA, UUID.randomUUID().toString(), "SECOND_REPORT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FROZEN"));
        // 이미 활성인 계좌의 해제도 그대로 성공
        unfreeze(accB, UUID.randomUUID().toString(), "NOTHING_TO_DO")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // 실제로 상태가 바뀐 것은 한 번뿐이므로 이벤트도 하나다. 처음 사유가 유지된다.
        assertThat(eventService.read(cursor, 100).events()).extracting(Event::eventType)
                .containsExactly("ACCOUNT_FROZEN");
        assertThat(accountRepository.findByAccountNumber(accA).orElseThrow().getFrozenReason())
                .isEqualTo("SUSPECTED_SCAM");

        // 같은 키를 다른 요청(해제, 다른 사유, 다른 계좌)에 쓰면 충돌
        unfreeze(accA, key, "SUSPECTED_SCAM")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        freeze(accA, key, "OTHER_REASON")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        freeze(accB, key, "SUSPECTED_SCAM")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        assertThat(accountRepository.findByAccountNumber(accB).orElseThrow().getStatus())
                .isEqualTo(Account.AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("지급정지 시점에 묶여 있던 금액은 유지되고, 그 보류 거래는 정지 중 실행할 수 없지만 취소는 할 수 있다")
    void freeze_keepsHolds() throws Exception {
        fds.holdAll("NEW_PAYEE_HIGH_VALUE");
        Long toExecute = transferService.transfer(userId, transfer(accA, accB, "300000")).transactionId();
        Long toCancel = transferService.transfer(userId, transfer(accA, accB, "200000")).transactionId();

        freeze(accA, UUID.randomUUID().toString(), "SUSPECTED_SCAM").andExpect(status().isOk());

        // 홀드와 보류 거래는 그대로다
        assertThat(holdRepository.findByTransactionId(toExecute).orElseThrow().getStatus())
                .isEqualTo(AccountHold.Status.ACTIVE);
        assertThat(statusOf(toExecute)).isEqualTo(TransactionStatus.HELD);
        assertThat(availableOf(accA)).isEqualByComparingTo("500000");

        // 정지 중 실행: 재검증에서 걸려 422, 거래는 보류로 남는다
        mockMvc.perform(post("/internal/transactions/{id}/execute", toExecute)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_FROZEN"));
        assertThat(statusOf(toExecute)).isEqualTo(TransactionStatus.HELD);
        assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL);

        // 정지 중 취소: 돈이 나가는 것이 아니므로 가능하다
        mockMvc.perform(post("/internal/transactions/{id}/cancel", toCancel)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertThat(availableOf(accA)).isEqualByComparingTo("700000");

        // 해제한 뒤에는 보류 거래를 실행할 수 있다
        unfreeze(accA, UUID.randomUUID().toString(), "CUSTOMER_VERIFIED").andExpect(status().isOk());
        mockMvc.perform(post("/internal/transactions/{id}/execute", toExecute)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertThat(balanceOf(accA)).isEqualByComparingTo("700000");
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();
        assertThat(reconciliationService.trialBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("판정을 기다리는 사이에 계좌가 정지되면, 승인이 나도 돈은 나가지 않고 거래는 FAILED(ACCOUNT_FROZEN)가 된다")
    void frozenDuringEvaluation_failsApprovedTransfer() {
        fds.duringEvaluation(request -> {
            try {
                freeze(accA, UUID.randomUUID().toString(), "SUSPECTED_SCAM").andExpect(status().isOk());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        TransferRequest request = transfer(accA, accB, "300000");

        assertThatThrownBy(() -> transferService.transfer(userId, request))
                .isInstanceOf(AccountFrozenException.class);

        Transaction tx = transactionRepository.findByIdempotencyKey(request.idempotencyKey()).orElseThrow();
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.getFailureCode()).isEqualTo("ACCOUNT_FROZEN");
        assertThat(holdRepository.findByTransactionId(tx.getId()).orElseThrow().getStatus())
                .isEqualTo(AccountHold.Status.RELEASED);
        assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL);
        assertThat(availableOf(accA)).isEqualByComparingTo(INITIAL);
    }

    @Test
    @DisplayName("요청 오류: 없는 계좌 404, 해지된 계좌 422, 사유 누락 400, 멱등성 키 누락 400, 토큰 없음 401")
    void errors() throws Exception {
        freeze("00000000000000", UUID.randomUUID().toString(), "SUSPECTED_SCAM")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));

        String closed = open(BigDecimal.ZERO);
        accountService.closeAccount(closed, userId, UUID.randomUUID().toString());
        freeze(closed, UUID.randomUUID().toString(), "SUSPECTED_SCAM")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        mockMvc.perform(post("/internal/accounts/{no}/freeze", accA)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/internal/accounts/{no}/freeze", accA)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"SUSPECTED_SCAM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
        mockMvc.perform(post("/internal/accounts/{no}/freeze", accA)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"SUSPECTED_SCAM\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(accountRepository.findByAccountNumber(accA).orElseThrow().getStatus())
                .isEqualTo(Account.AccountStatus.ACTIVE);
    }

    // ---- 헬퍼 ----

    private ResultActions freeze(String accountNumber, String key, String reason) throws Exception {
        return call("freeze", accountNumber, key, reason);
    }

    private ResultActions unfreeze(String accountNumber, String key, String reason) throws Exception {
        return call("unfreeze", accountNumber, key, reason);
    }

    private ResultActions call(String action, String accountNumber, String key, String reason) throws Exception {
        return mockMvc.perform(post("/internal/accounts/{no}/" + action, accountNumber)
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"" + reason + "\"}"));
    }

    private String open(BigDecimal balance) {
        return accountService.openAccount(userId,
                new AccountOpenRequest(UUID.randomUUID().toString(), balance)).accountNumber();
    }

    private TransferRequest transfer(String from, String to, String amount) {
        return new TransferRequest(UUID.randomUUID().toString(), from, to, new BigDecimal(amount), "지급정지 테스트");
    }

    private TransactionStatus statusOf(Long txId) {
        return transactionRepository.findById(txId).orElseThrow().getStatus();
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
