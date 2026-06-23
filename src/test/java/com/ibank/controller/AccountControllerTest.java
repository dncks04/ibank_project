package com.ibank.controller;

import com.ibank.domain.account.dto.AccountResponse;
import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.user.entity.User;
import com.ibank.global.security.CustomUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
class AccountControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired WebApplicationContext wac;
    @MockitoBean AccountService accountService;

    MockMvc mockMvc;

    private static final Long USER_ID = 1L;
    private static final String ACC_NO = "20260604000001";

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(springSecurity())
                .build();
    }

    private CustomUserDetails mockUser() {
        User user = User.builder()
                .loginId("testuser").password("pass")
                .name("테스터").email("test@test.com")
                .role(User.UserRole.ROLE_USER).build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return new CustomUserDetails(user);
    }

    private AccountResponse sampleAccountResponse() {
        return new AccountResponse(1L, ACC_NO, new BigDecimal("10000"),
                Account.AccountStatus.ACTIVE, LocalDateTime.now());
    }

    // ── 계좌 개설 ──────────────────────────────────────────

    @Test
    @DisplayName("계좌 개설 성공 - 201 반환")
    void openAccount_success() throws Exception {
        given(accountService.openAccount(eq(USER_ID), any())).willReturn(sampleAccountResponse());

        mockMvc.perform(post("/api/accounts")
                        .with(user(mockUser())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\": \"open-key-1\", \"initialBalance\": 10000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accountNumber").value(ACC_NO))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("계좌 개설 - 인증 없으면 401 반환")
    void openAccount_unauthorized() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initialBalance\": 10000}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("계좌 개설 - initialBalance 누락 시 400 반환")
    void openAccount_missingBalance() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .with(user(mockUser())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── 내 계좌 목록 ─────────────────────────────────────

    @Test
    @DisplayName("내 계좌 목록 조회 성공 - 200 반환")
    void getMyAccounts_success() throws Exception {
        given(accountService.getMyAccounts(USER_ID)).willReturn(
                List.of(sampleAccountResponse(),
                        new AccountResponse(2L, "20260604000002", BigDecimal.ZERO,
                                Account.AccountStatus.ACTIVE, LocalDateTime.now())));

        mockMvc.perform(get("/api/accounts/me").with(user(mockUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].accountNumber").value(ACC_NO));
    }

    @Test
    @DisplayName("내 계좌 목록 - 인증 없으면 401 반환")
    void getMyAccounts_unauthorized() throws Exception {
        mockMvc.perform(get("/api/accounts/me"))
                .andExpect(status().isUnauthorized());
    }

    // ── 계좌 단건 조회 ───────────────────────────────────

    @Test
    @DisplayName("계좌 단건 조회 성공 - 200 반환")
    void getAccount_success() throws Exception {
        given(accountService.getAccount(ACC_NO, USER_ID)).willReturn(sampleAccountResponse());

        mockMvc.perform(get("/api/accounts/{accountNumber}", ACC_NO).with(user(mockUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountNumber").value(ACC_NO));
    }

    @Test
    @DisplayName("계좌 단건 조회 - 타인 계좌 접근 시 403 반환")
    void getAccount_forbidden() throws Exception {
        given(accountService.getAccount(ACC_NO, USER_ID))
                .willThrow(new AccountAccessDeniedException(ACC_NO));

        mockMvc.perform(get("/api/accounts/{accountNumber}", ACC_NO).with(user(mockUser())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("계좌 단건 조회 - 존재하지 않는 계좌 시 400 반환")
    void getAccount_notFound() throws Exception {
        given(accountService.getAccount(ACC_NO, USER_ID))
                .willThrow(new IllegalArgumentException("계좌를 찾을 수 없습니다: " + ACC_NO));

        mockMvc.perform(get("/api/accounts/{accountNumber}", ACC_NO).with(user(mockUser())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── 계좌 해지 ─────────────────────────────────────────

    @Test
    @DisplayName("계좌 해지 성공 - 200 반환")
    void closeAccount_success() throws Exception {
        willDoNothing().given(accountService).closeAccount(ACC_NO, USER_ID, "close-key-1");

        mockMvc.perform(delete("/api/accounts/{accountNumber}", ACC_NO)
                        .param("idempotencyKey", "close-key-1")
                        .with(user(mockUser())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("계좌 해지 - 잔액 있으면 409 반환")
    void closeAccount_nonZeroBalance() throws Exception {
        willThrow(new IllegalStateException("잔액이 남아있는 계좌는 해지할 수 없습니다. 잔액: 10000"))
                .given(accountService).closeAccount(ACC_NO, USER_ID, "close-key-1");

        mockMvc.perform(delete("/api/accounts/{accountNumber}", ACC_NO)
                        .param("idempotencyKey", "close-key-1")
                        .with(user(mockUser())).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("계좌 해지 - 타인 계좌 접근 시 403 반환")
    void closeAccount_forbidden() throws Exception {
        willThrow(new AccountAccessDeniedException(ACC_NO))
                .given(accountService).closeAccount(ACC_NO, USER_ID, "close-key-1");

        mockMvc.perform(delete("/api/accounts/{accountNumber}", ACC_NO)
                        .param("idempotencyKey", "close-key-1")
                        .with(user(mockUser())).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("계좌 해지 - 인증 없으면 401 반환")
    void closeAccount_unauthorized() throws Exception {
        mockMvc.perform(delete("/api/accounts/{accountNumber}", ACC_NO))
                .andExpect(status().isUnauthorized());
    }
}
