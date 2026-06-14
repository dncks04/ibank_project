package com.ibank.controller;

import com.ibank.domain.fraud.entity.FraudAlert;
import com.ibank.domain.fraud.service.FraudReviewService;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이상거래 경보 검토 관리자 API 인가 검증.
 * {@code /api/admin/**}는 ROLE_ADMIN만 접근 가능해야 한다(일반 사용자 403, 미인증 401).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
class FraudAlertControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired WebApplicationContext wac;
    @MockitoBean FraudReviewService fraudReviewService;

    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("ADMIN은 경보 목록을 조회할 수 있다 - 200")
    void list_asAdmin_ok() throws Exception {
        given(fraudReviewService.list(FraudAlert.Status.OPEN)).willReturn(List.of());

        mockMvc.perform(get("/api/admin/fraud-alerts")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("ADMIN은 보류 이체를 승인할 수 있다 - 200")
    void release_asAdmin_ok() throws Exception {
        given(fraudReviewService.release(anyLong())).willReturn(new TransferResponse(
                1L, "key", "111", "222", new BigDecimal("5000"),
                Transaction.TransactionStatus.COMPLETED, LocalDateTime.now()));

        mockMvc.perform(post("/api/admin/fraud-alerts/{id}/release", 1L)
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("일반 사용자는 관리자 API에 접근할 수 없다 - 403")
    void list_asUser_forbidden() throws Exception {
        mockMvc.perform(get("/api/admin/fraud-alerts")
                        .with(user("u").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("미인증 사용자는 관리자 API에 접근할 수 없다 - 401")
    void release_unauthenticated_unauthorized() throws Exception {
        mockMvc.perform(post("/api/admin/fraud-alerts/{id}/reject", 1L).with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
