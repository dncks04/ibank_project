package com.ibank.outbox;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.entity.InsufficientBalanceException;
import com.ibank.domain.account.exception.InactiveAccountException;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.decision.FraudDecisionUnavailableException;
import com.ibank.domain.outbox.OutboxMaintenanceService;
import com.ibank.domain.transaction.dto.DepositRequest;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.service.TransactionProcessor;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.global.security.JwtProvider;
import com.ibank.internal.event.InternalEventService;
import com.ibank.internal.event.InternalEventsResponse;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 거래·계좌의 상태 변화가 Outbox 이벤트로 남는지, 그리고 조회 API가 계약대로 내려 주는지.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
class OutboxEventFlowTest {

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
    @Autowired private InternalEventService eventService;
    @Autowired private OutboxMaintenanceService maintenanceService;
    @Autowired private TransferService transferService;
    @Autowired private TransactionProcessor processor;
    @Autowired private AccountService accountService;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long userId;
    private String accA;
    private String accB;
    /** 계좌 개설 이벤트까지 소비한 뒤의 위치. 각 테스트는 여기서부터 읽는다. */
    private long cursor;
    private long cursorBeforeAccounts;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        fds.reset();
        userId = userRepository.save(User.builder()
                .loginId("outbox-user").password("x").name("이벤트유저").email("outbox@test.com")
                .birthDate(LocalDate.of(1950, 1, 1)).role(User.UserRole.ROLE_USER).build()).getId();
        cursorBeforeAccounts = jdbcTemplate.queryForObject("SELECT last_id FROM outbox_publish_state", Long.class);
        accA = open(new BigDecimal("1000000"));
        accB = open(BigDecimal.ZERO);
        cursor = eventService.read(cursorBeforeAccounts, 100).nextAfter();
    }

    @Test
    @DisplayName("계좌 개설·해지는 ACCOUNT_OPENED / ACCOUNT_CLOSED 이벤트를 남긴다")
    void accountEvents() {
        List<Event> opened = eventService.read(cursorBeforeAccounts, 100).events();
        assertThat(opened).extracting(Event::eventType).containsExactly("ACCOUNT_OPENED", "ACCOUNT_OPENED");
        assertThat(opened.get(0).aggregateId()).isEqualTo(accA);
        assertThat(opened.get(0).payload().get("accountNumber").asString()).isEqualTo(accA);
        assertThat(opened.get(0).payload().get("customerId").asLong()).isEqualTo(userId);
        assertThat(opened.get(0).payload().get("accountType").asString()).isEqualTo("DEMAND");
        assertThat(opened.get(0).payload().get("initialBalance").asString()).isEqualTo("1000000.00");

        accountService.closeAccount(accB, userId, UUID.randomUUID().toString());

        assertThat(newEvents()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("ACCOUNT_CLOSED");
            assertThat(event.aggregateId()).isEqualTo(accB);
        });
    }

    @Test
    @DisplayName("승인된 이체는 TRANSACTION_REQUESTED → TRANSACTION_COMPLETED 순으로 이벤트를 남긴다")
    void approvedTransfer_events() {
        TransferResponse response = transferService.transfer(userId, transfer("300000"));

        List<Event> events = newEvents();
        assertThat(events).extracting(Event::eventType)
                .containsExactly("TRANSACTION_REQUESTED", "TRANSACTION_COMPLETED");
        assertThat(events).allSatisfy(event ->
                assertThat(event.aggregateId()).isEqualTo(String.valueOf(response.transactionId())));

        Event requested = events.get(0);
        assertThat(requested.payload().get("transactionId").asLong()).isEqualTo(response.transactionId());
        assertThat(requested.payload().get("type").asString()).isEqualTo("TRANSFER");
        assertThat(requested.payload().get("channel").asString()).isEqualTo("MOBILE");
        assertThat(requested.payload().get("status").asString()).isEqualTo("PENDING_REVIEW");
        assertThat(requested.payload().get("fromAccountNumber").asString()).isEqualTo(accA);
        assertThat(requested.payload().get("toAccountNumber").asString()).isEqualTo(accB);
        assertThat(requested.payload().get("amount").asString()).isEqualTo("300000.00");
        assertThat(requested.payload().get("customerId").asLong()).isEqualTo(userId);
        assertThat(requested.payload().get("decision").isNull()).isTrue(); // 아직 판정 전
        // 홀드 만료 시각과 발생 시각은 영업 타임존 오프셋이 붙어 나간다
        assertThat(OffsetDateTime.parse(requested.payload().get("holdExpiresAt").asString()).getOffset().getTotalSeconds())
                .isEqualTo(9 * 3600);
        assertThat(OffsetDateTime.parse(requested.occurredAt()).getOffset().getTotalSeconds()).isEqualTo(9 * 3600);

        Event completed = events.get(1);
        assertThat(completed.payload().get("status").asString()).isEqualTo("COMPLETED");
        assertThat(completed.payload().get("decision").get("decision").asString()).isEqualTo("APPROVE");
        assertThat(completed.payload().get("decision").get("decisionSource").asString()).isEqualTo("FDS");
        assertThat(completed.payload().get("decision").get("decisionId").asString())
                .isEqualTo("decision-" + response.transactionId());
    }

    @Test
    @DisplayName("보류 → 실행은 HELD → COMPLETED, 보류 → 취소는 HELD → CANCELLED 이벤트를 사유와 함께 남긴다")
    void heldThenResolved_events() {
        fds.holdAll("NEW_PAYEE_HIGH_VALUE");
        Long toExecute = transferService.transfer(userId, transfer("100000")).transactionId();
        Long toCancel = transferService.withdraw(userId,
                new WithdrawRequest(accA, new BigDecimal("50000"), UUID.randomUUID().toString(), "출금")).transactionId();
        cursor = eventService.read(cursor, 100).nextAfter(); // REQUESTED, HELD ×2는 아래에서 따로 확인하지 않고 넘긴다

        processor.executeHeld(toExecute, "GUARDIAN_APPROVED");
        processor.cancelHeld(toCancel, "CUSTOMER_CONFIRMED_SCAM");

        List<Event> events = newEvents();
        assertThat(events).extracting(Event::eventType)
                .containsExactly("TRANSACTION_COMPLETED", "TRANSACTION_CANCELLED");
        assertThat(events.get(0).payload().get("reasonCode").asString()).isEqualTo("GUARDIAN_APPROVED");
        assertThat(events.get(0).payload().get("decision").get("reasonCodes").get(0).asString())
                .isEqualTo("NEW_PAYEE_HIGH_VALUE");
        assertThat(events.get(1).payload().get("type").asString()).isEqualTo("WITHDRAWAL");
        assertThat(events.get(1).payload().get("toAccountNumber").isNull()).isTrue();
        assertThat(events.get(1).payload().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(events.get(1).payload().get("reasonCode").asString()).isEqualTo("CUSTOMER_CONFIRMED_SCAM");
    }

    @Test
    @DisplayName("판정이 보류면 TRANSACTION_HELD 이벤트에 판정 사유가 실리고, 대체 정책이면 decisionSource가 FALLBACK이다")
    void heldEvent_carriesDecision() {
        fds.failWith(new FraudDecisionUnavailableException("FDS 응답 없음")); // 고객이 고령이라 대체 정책이 보류한다

        transferService.transfer(userId, transfer("100000"));

        List<Event> events = newEvents();
        assertThat(events).extracting(Event::eventType).containsExactly("TRANSACTION_REQUESTED", "TRANSACTION_HELD");
        Event held = events.get(1);
        assertThat(held.payload().get("status").asString()).isEqualTo("HELD");
        assertThat(held.payload().get("decision").get("decision").asString()).isEqualTo("HOLD");
        assertThat(held.payload().get("decision").get("decisionSource").asString()).isEqualTo("FALLBACK");
        assertThat(held.payload().get("decision").get("decisionId").isNull()).isTrue();
        assertThat(held.payload().get("decision").get("reasonCodes").get(0).asString()).isEqualTo("FDS_UNAVAILABLE");
        assertThat(held.payload().get("holdExpiresAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("승인 후 재검증 실패는 TRANSACTION_FAILED 이벤트를 실패 사유와 함께 남긴다")
    void failedEvent() {
        fds.duringEvaluation(request -> accountService.closeAccount(accB, userId, UUID.randomUUID().toString()));

        assertThatThrownBy(() -> transferService.transfer(userId, transfer("100000")))
                .isInstanceOf(InactiveAccountException.class);

        List<Event> events = newEvents();
        assertThat(events).extracting(Event::eventType)
                .containsExactly("TRANSACTION_REQUESTED", "ACCOUNT_CLOSED", "TRANSACTION_FAILED");
        assertThat(events.get(2).payload().get("status").asString()).isEqualTo("FAILED");
        assertThat(events.get(2).payload().get("reasonCode").asString()).isEqualTo("ACCOUNT_NOT_ACTIVE");
    }

    @Test
    @DisplayName("입금은 DEPOSIT 이벤트를 남기고, 거절되어 롤백된 요청은 아무 이벤트도 남기지 않는다")
    void depositEvent_andNoEventOnRollback() {
        TransferResponse deposit = transferService.depositForUser(userId,
                new DepositRequest(accA, new BigDecimal("5000"), UUID.randomUUID().toString(), "입금"));
        // 가용잔액 부족: 가승인 단계에서 롤백된다
        assertThatThrownBy(() -> transferService.transfer(userId, transfer("2000000")))
                .isInstanceOf(InsufficientBalanceException.class);
        assertThatThrownBy(() -> transferService.withdraw(userId,
                new WithdrawRequest(accA, new BigDecimal("2000000"), UUID.randomUUID().toString(), "초과 출금")))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(newEvents()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("DEPOSIT");
            assertThat(event.aggregateId()).isEqualTo(String.valueOf(deposit.transactionId()));
            assertThat(event.payload().get("accountNumber").asString()).isEqualTo(accA);
            assertThat(event.payload().get("amount").asString()).isEqualTo("5000.00");
            assertThat(event.payload().get("customerId").asLong()).isEqualTo(userId);
            assertThat(event.payload().get("channel").asString()).isEqualTo("MOBILE");
        });
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE id IS NULL", Long.class))
                .isZero();
    }

    // ---- 조회 API ----

    @Test
    @DisplayName("GET /internal/events: after 이후를 limit만큼 주고, hasMore와 nextAfter로 이어서 가져갈 수 있다")
    void eventsApi_paging() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        String token = jwtProvider.generateSystemToken("fds-service", Duration.ofMinutes(5));
        transferService.transfer(userId, transfer("1000"));
        transferService.transfer(userId, transfer("2000")); // 이벤트 4건

        mockMvc.perform(get("/internal/events").param("after", String.valueOf(cursor)).param("limit", "3")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events.length()").value(3))
                .andExpect(jsonPath("$.data.events[0].id").value(cursor + 1))
                .andExpect(jsonPath("$.data.events[0].eventType").value("TRANSACTION_REQUESTED"))
                .andExpect(jsonPath("$.data.events[0].payload.amount").value("1000.00"))
                .andExpect(jsonPath("$.data.events[2].id").value(cursor + 3))
                .andExpect(jsonPath("$.data.nextAfter").value(cursor + 3))
                .andExpect(jsonPath("$.data.hasMore").value(true));

        mockMvc.perform(get("/internal/events").param("after", String.valueOf(cursor + 3)).param("limit", "3")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events.length()").value(1))
                .andExpect(jsonPath("$.data.events[0].eventType").value("TRANSACTION_COMPLETED"))
                .andExpect(jsonPath("$.data.nextAfter").value(cursor + 4))
                .andExpect(jsonPath("$.data.hasMore").value(false));

        // 더 없으면 빈 목록과 요청한 after 그대로
        mockMvc.perform(get("/internal/events").param("after", String.valueOf(cursor + 4))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events.length()").value(0))
                .andExpect(jsonPath("$.data.nextAfter").value(cursor + 4))
                .andExpect(jsonPath("$.data.hasMore").value(false));

        // 같은 위치를 다시 물으면 같은 이벤트가 다시 내려간다 (at-least-once)
        mockMvc.perform(get("/internal/events").param("after", String.valueOf(cursor)).param("limit", "3")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.events[0].id").value(cursor + 1));

        // 범위를 벗어난 파라미터, 토큰 없는 요청
        mockMvc.perform(get("/internal/events").param("limit", "1001").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/internal/events"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("정리: 보관 기간이 지난 '공개된' 이벤트만 지우고, 지운 뒤에도 번호는 이어진다")
    void cleanup_keepsUnpublishedAndNumbering() {
        transferService.transfer(userId, transfer("1000"));
        long lastPublished = eventService.read(cursor, 100).nextAfter();
        // 아직 공개되지 않은 이벤트를 하나 만들고, 모든 이벤트를 60일 전 것으로 돌린다
        transferService.depositForUser(userId,
                new DepositRequest(accA, new BigDecimal("1"), UUID.randomUUID().toString(), "미공개"));
        jdbcTemplate.update("UPDATE outbox_events SET created_at = created_at - INTERVAL '60 days'");

        int deleted = maintenanceService.purgePublishedEventsOlderThan(Duration.ofDays(30));

        assertThat(deleted).isEqualTo(4); // 계좌 개설 2 + 이체 2
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).isEqualTo(1);
        // 남아 있던 미공개 이벤트는 다음 번호로 공개된다 (번호가 되돌아가지 않는다)
        assertThat(eventService.read(lastPublished, 100).events()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("DEPOSIT");
            assertThat(event.id()).isEqualTo(lastPublished + 1);
        });
    }

    // ---- 헬퍼 ----

    /** 마지막으로 읽은 위치 이후의 이벤트를 읽고 위치를 옮긴다. */
    private List<Event> newEvents() {
        InternalEventsResponse page = eventService.read(cursor, 100);
        cursor = page.nextAfter();
        return page.events();
    }

    private String open(BigDecimal balance) {
        return accountService.openAccount(userId,
                new AccountOpenRequest(UUID.randomUUID().toString(), balance)).accountNumber();
    }

    private TransferRequest transfer(String amount) {
        return new TransferRequest(UUID.randomUUID().toString(), accA, accB, new BigDecimal(amount), "이벤트");
    }
}
