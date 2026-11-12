package com.ibank.internal;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.transaction.dto.DepositRequest;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.global.security.JwtProvider;
import com.ibank.global.time.BusinessClock;
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
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 판정용 고객·거래 정보 내부 API. 판정에 필요한 최소 정보만 나가는지(개인정보 원본이 새지 않는지)와
 * 거래 이력의 방향·기간 경계를 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
class InternalCustomerApiTest {

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
    @Autowired private AccountService accountService;
    @Autowired private UserRepository userRepository;
    @Autowired private BusinessClock businessClock;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private String token;
    private Long userId;
    private String accA;
    private String accB;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        fds.reset();
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        token = jwtProvider.generateSystemToken("fds-service", Duration.ofMinutes(5));

        userId = userRepository.save(User.builder()
                .loginId("profile-user").password("secret-hash").name("김어르신").email("elder.kim@private.test")
                .birthDate(businessClock.today().minusYears(72).minusDays(10))
                .role(User.UserRole.ROLE_USER).build()).getId();
        accA = open(new BigDecimal("1000000"));
        accB = open(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("고객 정보: 나이·가입일·보유 계좌만 나가고 이름·이메일·생년월일 원본은 나가지 않는다")
    void profile_minimalInformation() throws Exception {
        accountService.closeAccount(accB, userId, UUID.randomUUID().toString());

        mockMvc.perform(authorized(get("/internal/customers/{id}/profile", userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerId").value(userId))
                .andExpect(jsonPath("$.data.age").value(72))
                .andExpect(jsonPath("$.data.joinedAt").exists())
                .andExpect(jsonPath("$.data.accounts.length()").value(2))
                .andExpect(jsonPath("$.data.accounts[?(@.accountNumber == '%s')].status".formatted(accA)).value("ACTIVE"))
                .andExpect(jsonPath("$.data.accounts[?(@.accountNumber == '%s')].status".formatted(accB)).value("CLOSED"))
                .andExpect(jsonPath("$.data.accounts[0].accountType").value("DEMAND"))
                // 판정에 필요 없는 개인정보는 응답 어디에도 없어야 한다
                .andExpect(content().string(not(containsString("김어르신"))))
                .andExpect(content().string(not(containsString("elder.kim"))))
                .andExpect(content().string(not(containsString("secret-hash"))))
                .andExpect(content().string(not(containsString("birthDate"))))
                .andExpect(content().string(not(containsString("balance"))));
    }

    @Test
    @DisplayName("고객 정보: 생년월일이 없는 고객은 age가 null이고, 없는 고객은 404 CUSTOMER_NOT_FOUND")
    void profile_unknownAgeAndNotFound() throws Exception {
        Long legacy = userRepository.save(User.builder()
                .loginId("legacy-user").password("x").name("기존").email("legacy@private.test")
                .role(User.UserRole.ROLE_USER).build()).getId();

        mockMvc.perform(authorized(get("/internal/customers/{id}/profile", legacy)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.age").doesNotExist())
                .andExpect(jsonPath("$.data.accounts.length()").value(0));

        mockMvc.perform(authorized(get("/internal/customers/{id}/profile", 999_999)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
    }

    @Test
    @DisplayName("거래 이력: 최신순으로 방향·상대 계좌·상태·채널이 실리고, 고객이 적은 메모는 나가지 않는다")
    void transactions_history() throws Exception {
        transferService.depositForUser(userId,
                new DepositRequest(accA, new BigDecimal("5000"), UUID.randomUUID().toString(), "용돈 비밀메모"));
        transferService.transfer(userId, transfer("30000", "병원비 비밀메모"));
        fds.holdAll("NEW_PAYEE_HIGH_VALUE");
        TransferResponse held = transferService.transfer(userId, transfer("700000", "보류될 이체"));

        // 출금 계좌 기준: 보류 이체(OUT) → 완료 이체(OUT) → 입금(IN) 순
        mockMvc.perform(authorized(get("/internal/accounts/{no}/transactions", accA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0].transactionId").value(held.transactionId()))
                .andExpect(jsonPath("$.data.content[0].status").value("HELD"))
                .andExpect(jsonPath("$.data.content[0].direction").value("OUT"))
                .andExpect(jsonPath("$.data.content[0].amount").value("700000.00"))
                .andExpect(jsonPath("$.data.content[0].counterpartAccountNumber").value(accB))
                .andExpect(jsonPath("$.data.content[0].completedAt").doesNotExist())
                .andExpect(jsonPath("$.data.content[1].type").value("TRANSFER"))
                .andExpect(jsonPath("$.data.content[1].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.content[1].channel").value("MOBILE"))
                .andExpect(jsonPath("$.data.content[1].completedAt").exists())
                .andExpect(jsonPath("$.data.content[2].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.data.content[2].direction").value("IN"))
                .andExpect(jsonPath("$.data.content[2].counterpartAccountNumber").doesNotExist())
                .andExpect(content().string(not(containsString("비밀메모"))));

        // 입금 계좌 기준으로는 같은 이체가 IN으로 보인다
        mockMvc.perform(authorized(get("/internal/accounts/{no}/transactions", accB)))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].direction").value("IN"))
                .andExpect(jsonPath("$.data.content[0].counterpartAccountNumber").value(accA));
    }

    @Test
    @DisplayName("거래 이력: 기간은 [from, to) — from 시각의 거래는 포함되고 to 시각의 거래는 빠진다")
    void transactions_halfOpenRange() throws Exception {
        Long first = transferService.transfer(userId, transfer("1000", "t1")).transactionId();
        Long second = transferService.transfer(userId, transfer("2000", "t2")).transactionId();
        Long third = transferService.transfer(userId, transfer("3000", "t3")).transactionId();
        // 요청 시각을 한 시간 간격으로 고정한다 (저장 타임존 기준 시각을 직접 넣는다)
        OffsetDateTime base = OffsetDateTime.parse("2026-11-10T09:00:00+09:00");
        setCreatedAt(first, base);
        setCreatedAt(second, base.plusHours(1));
        setCreatedAt(third, base.plusHours(2));

        // [09:00, 11:00) → 09:00과 10:00의 거래만
        mockMvc.perform(authorized(get("/internal/accounts/{no}/transactions", accA))
                        .param("from", iso(base)).param("to", iso(base.plusHours(2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].transactionId").value(second))
                .andExpect(jsonPath("$.data.content[1].transactionId").value(first))
                // 응답 시각은 영업 타임존 오프셋으로 나간다
                .andExpect(jsonPath("$.data.content[1].createdAt").value("2026-11-10T09:00:00+09:00"));

        // 이어지는 구간 [11:00, 12:00) → 경계의 거래가 앞 구간과 겹치지도 빠지지도 않는다
        mockMvc.perform(authorized(get("/internal/accounts/{no}/transactions", accA))
                        .param("from", iso(base.plusHours(2))).param("to", iso(base.plusHours(3))))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].transactionId").value(third));

        // 같은 순간을 다른 오프셋(UTC)으로 물어도 결과가 같다
        mockMvc.perform(authorized(get("/internal/accounts/{no}/transactions", accA))
                        .param("from", "2026-11-10T00:00:00Z").param("to", "2026-11-10T02:00:00Z"))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @DisplayName("거래 이력: 페이징, 없는 계좌 404, 잘못된 파라미터 400, 시스템 토큰 없으면 401")
    void transactions_pagingAndErrors() throws Exception {
        for (int i = 1; i <= 5; i++) {
            transferService.transfer(userId, transfer(String.valueOf(i * 1000), "p" + i));
        }

        mockMvc.perform(authorized(get("/internal/accounts/{no}/transactions", accA))
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.content[0].amount").value("3000.00"));

        mockMvc.perform(authorized(get("/internal/accounts/{no}/transactions", "00000000000000")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mockMvc.perform(authorized(get("/internal/accounts/{no}/transactions", accA)).param("size", "501"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(authorized(get("/internal/accounts/{no}/transactions", accA)).param("from", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(authorized(get("/internal/accounts/{no}/transactions", accA))
                        .param("from", "2026-11-10T10:00:00+09:00").param("to", "2026-11-10T09:00:00+09:00"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/internal/accounts/{no}/transactions", accA))
                .andExpect(status().isUnauthorized());
    }

    // ---- 헬퍼 ----

    private void setCreatedAt(Long transactionId, OffsetDateTime at) {
        jdbcTemplate.update("UPDATE transactions SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(businessClock.toStorage(at)), transactionId);
    }

    private static String iso(OffsetDateTime time) {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time);
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String open(BigDecimal balance) {
        return accountService.openAccount(userId,
                new AccountOpenRequest(UUID.randomUUID().toString(), balance)).accountNumber();
    }

    private TransferRequest transfer(String amount, String memo) {
        return new TransferRequest(UUID.randomUUID().toString(), accA, accB, new BigDecimal(amount), memo);
    }
}
