package com.ibank.teller;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.ledger.service.ReconciliationService;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.exception.IdempotencyKeyConflictException;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.dto.LoginRequest;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.domain.user.service.StaffBootstrap;
import com.ibank.domain.user.service.UserService;
import com.ibank.global.audit.AuditLog;
import com.ibank.global.audit.AuditLogRepository;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 창구 채널. 설정으로 만든 창구 직원이 실제로 로그인해 받은 토큰으로 고객 계좌에서 출금한다.
 * 고객 출금과 같은 2단계 처리를 타면서 채널·감사 기록·권한 경계가 창구용으로 맞는지 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "ibank.bootstrap.staff.teller01.password=teller-pass-1234",
        "ibank.bootstrap.staff.teller01.role=ROLE_TELLER",
        "ibank.bootstrap.staff.teller01.name=김창구"
})
@Testcontainers(disabledWithoutDocker = true)
class TellerWithdrawalTest {

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
    @Autowired private StaffBootstrap staffBootstrap;
    @Autowired private UserService userService;
    @Autowired private TransferService transferService;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private InternalEventService eventService;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private String tellerToken;
    private String customerToken;
    private Long customerId;
    private String account;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        fds.reset();
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();

        // 기동 때 만들어진 직원 계정은 위에서 지워졌다. 같은 설정으로 다시 만든다.
        assertThat(staffBootstrap.createMissingStaff()).isEqualTo(1);
        tellerToken = userService.login(new LoginRequest("teller01", "teller-pass-1234")).accessToken();

        customerId = userRepository.save(User.builder()
                .loginId("teller-customer").password(passwordEncoder.encode("customer-pass-1"))
                .name("박고객").email("customer@teller.test")
                .birthDate(LocalDate.of(1948, 3, 1)).role(User.UserRole.ROLE_USER).build()).getId();
        customerToken = userService.login(new LoginRequest("teller-customer", "customer-pass-1")).accessToken();
        account = accountService.openAccount(customerId,
                new AccountOpenRequest(UUID.randomUUID().toString(), new BigDecimal("5000000"))).accountNumber();
    }

    @Test
    @DisplayName("직원 계정 생성은 한 번뿐이고 역할은 설정대로다 (다시 돌려도 덮어쓰지 않는다)")
    void staffBootstrap_isIdempotent() {
        User teller = userRepository.findByLoginId("teller01").orElseThrow();
        assertThat(teller.getRole()).isEqualTo(User.UserRole.ROLE_TELLER);
        assertThat(teller.getName()).isEqualTo("김창구");
        assertThat(teller.getPassword()).isNotEqualTo("teller-pass-1234"); // 해시로 저장

        assertThat(staffBootstrap.createMissingStaff()).isZero();
        assertThat(userRepository.findAll().stream().filter(u -> u.getRole() == User.UserRole.ROLE_TELLER)).hasSize(1);
    }

    @Test
    @DisplayName("창구 직원이 고객 계좌에서 출금한다: 채널 TELLER, 판정 요청의 고객은 계좌 주인, 감사 로그에는 직원이 남는다")
    void tellerWithdraws() throws Exception {
        mockMvc.perform(post("/api/teller/withdrawals").header("Authorization", "Bearer " + tellerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("key-teller-1", "300000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.fromAccountNumber").value(account));

        assertThat(balanceOf()).isEqualByComparingTo("4700000");
        Transaction tx = transactionRepository.findByIdempotencyKey("key-teller-1").orElseThrow();
        assertThat(tx.getChannel()).isEqualTo(Transaction.Channel.TELLER);
        assertThat(tx.getType()).isEqualTo(Transaction.TransactionType.WITHDRAWAL);

        // FDS에는 창구 거래임과 계좌 주인(직원이 아니라 고객)이 전달된다
        assertThat(fds.requests()).singleElement().satisfies(request -> {
            assertThat(request.channel()).isEqualTo(Transaction.Channel.TELLER);
            assertThat(request.customerId()).isEqualTo(customerId);
            assertThat(request.fromAccountNumber()).isEqualTo(account);
        });
        // 이벤트에도 채널이 실린다
        List<Event> events = eventService.read(0, 100).events();
        assertThat(events).filteredOn(e -> e.eventType().startsWith("TRANSACTION_"))
                .allSatisfy(e -> assertThat(e.payload().get("channel").asString()).isEqualTo("TELLER"));

        // 누가 처리했는지는 감사 로그의 actor로 남는다
        assertThat(audits("TELLER_WITHDRAW")).singleElement().satisfies(audit -> {
            assertThat(audit.getActor()).isEqualTo("teller01");
            assertThat(audit.getTarget()).isEqualTo(account);
            assertThat(audit.getResult()).isEqualTo("SUCCESS");
        });
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();
        assertThat(reconciliationService.trialBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("창구 출금이 보류되면 응답에 사유 코드가 실려 창구 화면이 확인 질문을 띄울 수 있다")
    void tellerWithdrawal_held_returnsReasonCodes() throws Exception {
        fds.holdAll("LARGE_CASH_WITHDRAWAL", "ELDER_UNUSUAL_PATTERN");

        mockMvc.perform(post("/api/teller/withdrawals").header("Authorization", "Bearer " + tellerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("key-teller-held", "3000000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("HELD"))
                .andExpect(jsonPath("$.data.reasonCodes[0]").value("LARGE_CASH_WITHDRAWAL"))
                .andExpect(jsonPath("$.data.reasonCodes[1]").value("ELDER_UNUSUAL_PATTERN"));

        // 현금은 나가지 않았고 금액만 묶였다
        assertThat(balanceOf()).isEqualByComparingTo("5000000");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM account_holds WHERE status = 'ACTIVE'", BigDecimal.class))
                .isEqualByComparingTo("3000000");

        // 같은 키로 다시 누르면 같은 보류 결과가 돌아온다 (창구 화면의 재시도)
        mockMvc.perform(post("/api/teller/withdrawals").header("Authorization", "Bearer " + tellerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("key-teller-held", "3000000")))
                .andExpect(jsonPath("$.data.status").value("HELD"))
                .andExpect(jsonPath("$.data.reasonCodes[0]").value("LARGE_CASH_WITHDRAWAL"));
        assertThat(fds.requests()).hasSize(1);
    }

    @Test
    @DisplayName("창구 출금도 가용잔액과 한도를 지킨다")
    void tellerWithdrawal_respectsBalanceAndLimits() throws Exception {
        mockMvc.perform(post("/api/teller/withdrawals").header("Authorization", "Bearer " + tellerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID().toString(), "5000001")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/teller/withdrawals").header("Authorization", "Bearer " + tellerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID().toString(), "10000001")))
                .andExpect(status().isBadRequest()); // 단건 한도 초과

        assertThat(balanceOf()).isEqualByComparingTo("5000000");
        assertThat(transactionRepository.count()).isZero();
        assertThat(audits("TELLER_WITHDRAW")).hasSize(2).allSatisfy(a -> assertThat(a.getResult()).isEqualTo("FAILURE"));
    }

    @Test
    @DisplayName("권한 경계: 고객은 창구 API를 쓸 수 없고, 창구 직원은 고객 API를 쓸 수 없다")
    void roleBoundaries() throws Exception {
        mockMvc.perform(post("/api/teller/withdrawals").header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID().toString(), "1000")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/teller/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID().toString(), "1000")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + tellerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/transactions/withdraw").header("Authorization", "Bearer " + tellerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID().toString(), "1000")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/events").header("Authorization", "Bearer " + tellerToken))
                .andExpect(status().isForbidden());

        assertThat(balanceOf()).isEqualByComparingTo("5000000");
    }

    @Test
    @DisplayName("고객이 앱에서 쓴 멱등성 키가 창구에서 들어오면 같은 요청으로 보지 않고 충돌로 거부한다")
    void sameKeyAcrossChannels_conflict() {
        // 직원은 ROLE_TELLER, 고객은 ROLE_USER 경로로만 들어오므로 서비스 계층에서 직접 확인한다
        transferService.withdraw(customerId,
                new WithdrawRequest(account, new BigDecimal("1000"), "shared-key", "앱 출금"));

        assertThatThrownBy(() -> transferService.withdrawAtTeller(
                new WithdrawRequest(account, new BigDecimal("1000"), "shared-key", "창구 출금")))
                .isInstanceOf(IdempotencyKeyConflictException.class);

        assertThat(balanceOf()).isEqualByComparingTo("4999000"); // 한 번만 출금
    }

    // ---- 헬퍼 ----

    private String body(String key, String amount) {
        return "{\"accountNumber\":\"%s\",\"amount\":%s,\"idempotencyKey\":\"%s\",\"description\":\"창구 출금\"}"
                .formatted(account, amount, key);
    }

    private BigDecimal balanceOf() {
        return accountRepository.findByAccountNumber(account).orElseThrow().getBalance();
    }

    private List<AuditLog> audits(String action) {
        return auditLogRepository.findAll().stream().filter(log -> action.equals(log.getAction())).toList();
    }
}
