package com.ibank.controller;

import com.ibank.domain.account.entity.InsufficientBalanceException;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.domain.transaction.dto.TransactionHistoryResponse;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.service.TransactionQueryService;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.global.security.CustomUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
class TransactionControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired WebApplicationContext wac;
    @MockitoBean TransferService transferService;
    @MockitoBean TransactionQueryService transactionQueryService;

    MockMvc mockMvc;

    private static final Long USER_ID = 1L;
    private static final String ACC_A = "20260604000001";
    private static final String ACC_B = "20260604000002";

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

    private TransferResponse sampleTransferResponse(String fromAcc, String toAcc) {
        return new TransferResponse(1L, UUID.randomUUID().toString(), fromAcc, toAcc,
                new BigDecimal("5000"), Transaction.TransactionStatus.COMPLETED, LocalDateTime.now());
    }

    // ── 입금 ──────────────────────────────────────────────

    @Test
    @DisplayName("입금 성공 - 200 반환")
    void deposit_success() throws Exception {
        given(transferService.depositForUser(eq(USER_ID), any()))
                .willReturn(sampleTransferResponse(null, ACC_A));

        mockMvc.perform(post("/api/transactions/deposit")
                        .with(user(mockUser())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountNumber": "%s",
                                  "amount": 5000,
                                  "idempotencyKey": "%s",
                                  "description": "테스트 입금"
                                }
                                """.formatted(ACC_A, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.toAccountNumber").value(ACC_A));
    }

    @Test
    @DisplayName("입금 - amount가 0 이하이면 400 반환")
    void deposit_invalidAmount() throws Exception {
        mockMvc.perform(post("/api/transactions/deposit")
                        .with(user(mockUser())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountNumber": "%s",
                                  "amount": 0,
                                  "idempotencyKey": "%s"
                                }
                                """.formatted(ACC_A, UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("입금 - 타인 계좌 접근 시 403 반환")
    void deposit_forbidden() throws Exception {
        willThrow(new AccountAccessDeniedException(ACC_A))
                .given(transferService).depositForUser(eq(USER_ID), any());

        mockMvc.perform(post("/api/transactions/deposit")
                        .with(user(mockUser())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountNumber": "%s",
                                  "amount": 5000,
                                  "idempotencyKey": "%s"
                                }
                                """.formatted(ACC_A, UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("입금 - 인증 없으면 401 반환")
    void deposit_unauthorized() throws Exception {
        mockMvc.perform(post("/api/transactions/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountNumber": "%s",
                                  "amount": 5000,
                                  "idempotencyKey": "%s"
                                }
                                """.formatted(ACC_A, UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    // ── 출금 ──────────────────────────────────────────────

    @Test
    @DisplayName("출금 성공 - 200 반환")
    void withdraw_success() throws Exception {
        given(transferService.withdraw(eq(USER_ID), any()))
                .willReturn(sampleTransferResponse(ACC_A, null));

        mockMvc.perform(post("/api/transactions/withdraw")
                        .with(user(mockUser())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountNumber": "%s",
                                  "amount": 5000,
                                  "idempotencyKey": "%s",
                                  "description": "테스트 출금"
                                }
                                """.formatted(ACC_A, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.fromAccountNumber").value(ACC_A));
    }

    @Test
    @DisplayName("출금 - 잔액 부족 시 422 반환")
    void withdraw_insufficientBalance() throws Exception {
        willThrow(new InsufficientBalanceException(ACC_A, new BigDecimal("1000"), new BigDecimal("9999")))
                .given(transferService).withdraw(eq(USER_ID), any());

        mockMvc.perform(post("/api/transactions/withdraw")
                        .with(user(mockUser())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountNumber": "%s",
                                  "amount": 9999,
                                  "idempotencyKey": "%s"
                                }
                                """.formatted(ACC_A, UUID.randomUUID())))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── 이체 ──────────────────────────────────────────────

    @Test
    @DisplayName("이체 성공 - 200 반환")
    void transfer_success() throws Exception {
        given(transferService.transfer(any()))
                .willReturn(sampleTransferResponse(ACC_A, ACC_B));

        mockMvc.perform(post("/api/transactions/transfer")
                        .with(user(mockUser())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idempotencyKey": "%s",
                                  "fromAccountNumber": "%s",
                                  "toAccountNumber": "%s",
                                  "amount": 5000,
                                  "description": "테스트 이체"
                                }
                                """.formatted(UUID.randomUUID(), ACC_A, ACC_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.fromAccountNumber").value(ACC_A))
                .andExpect(jsonPath("$.data.toAccountNumber").value(ACC_B));
    }

    @Test
    @DisplayName("이체 - idempotencyKey 누락 시 400 반환")
    void transfer_missingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/transactions/transfer")
                        .with(user(mockUser())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fromAccountNumber": "%s",
                                  "toAccountNumber": "%s",
                                  "amount": 5000
                                }
                                """.formatted(ACC_A, ACC_B)))
                .andExpect(status().isBadRequest());
    }

    // ── 거래 내역 ─────────────────────────────────────────

    @Test
    @DisplayName("거래 내역 조회 성공 - 페이지 반환")
    void getHistory_success() throws Exception {
        TransactionHistoryResponse tx = new TransactionHistoryResponse(1L,
                Transaction.TransactionType.TRANSFER, Transaction.TransactionStatus.COMPLETED,
                new BigDecimal("5000"), ACC_B, "테스트 이체", LocalDateTime.now());

        given(transactionQueryService.getHistory(eq(ACC_A), eq(USER_ID), any()))
                .willReturn(new PageImpl<>(List.of(tx), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/accounts/{accountNumber}/transactions", ACC_A)
                        .with(user(mockUser()))
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$.data.content[0].amount").value(5000))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("거래 내역 조회 - 타인 계좌 접근 시 403 반환")
    void getHistory_forbidden() throws Exception {
        willThrow(new AccountAccessDeniedException(ACC_A))
                .given(transactionQueryService).getHistory(eq(ACC_A), eq(USER_ID), any());

        mockMvc.perform(get("/api/accounts/{accountNumber}/transactions", ACC_A)
                        .with(user(mockUser())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("거래 내역 조회 - 인증 없으면 401 반환")
    void getHistory_unauthorized() throws Exception {
        mockMvc.perform(get("/api/accounts/{accountNumber}/transactions", ACC_A))
                .andExpect(status().isUnauthorized());
    }
}
