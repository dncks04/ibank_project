package com.ibank.controller;

import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.search.MonthlySummaryRow;
import com.ibank.domain.transaction.search.TransactionRow;
import com.ibank.domain.transaction.search.TransactionSearchCondition;
import com.ibank.domain.transaction.search.TransactionSearchService;
import com.ibank.domain.user.entity.User;
import com.ibank.global.response.PageResponse;
import com.ibank.global.security.CustomUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검색·리포트 엔드포인트의 요청 파라미터 바인딩 검증.
 *
 * <p>매퍼 동작은 {@code TransactionSearchTest}가 실제 DB로 확인한다. 여기서는 쿼리 파라미터가
 * enum과 {@code List<enum>}으로 제대로 묶이는지, 즉 웹 계층 경계만 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers(disabledWithoutDocker = true)
class TransactionSearchControllerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired WebApplicationContext wac;
    @MockitoBean TransactionSearchService transactionSearchService;

    MockMvc mockMvc;

    private static final Long USER_ID = 1L;
    private static final String ACC = "30260201000001";

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
                .apply(springSecurity())
                .build();
    }

    private CustomUserDetails mockUser() {
        User user = User.builder()
                .loginId("searchuser").password("pass")
                .name("테스터").email("search@test.com")
                .role(User.UserRole.ROLE_USER).build();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        return new CustomUserDetails(user);
    }

    @Test
    @DisplayName("쿼리 파라미터가 조건 객체로 묶여 서비스에 전달된다")
    void search_bindsQueryParametersIntoCondition() throws Exception {
        given(transactionSearchService.search(anyString(), anyLong(), any()))
                .willReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/accounts/{no}/transactions/search", ACC)
                        .param("types", "TRANSFER", "DEPOSIT")
                        .param("statuses", "COMPLETED")
                        .param("direction", "OUT")
                        .param("sort", "AMOUNT")
                        .param("minAmount", "10000")
                        .param("keyword", "월세")
                        .param("from", "2026-01-01T00:00:00")
                        .param("page", "1")
                        .param("size", "10")
                        .with(user(mockUser())))
                .andExpect(status().isOk());

        ArgumentCaptor<TransactionSearchCondition> captor =
                ArgumentCaptor.forClass(TransactionSearchCondition.class);
        verify(transactionSearchService).search(any(), any(), captor.capture());

        TransactionSearchCondition c = captor.getValue();
        assertThat(c.types()).containsExactly(
                Transaction.TransactionType.TRANSFER, Transaction.TransactionType.DEPOSIT);
        assertThat(c.statuses()).containsExactly(Transaction.TransactionStatus.COMPLETED);
        assertThat(c.direction()).isEqualTo(TransactionSearchCondition.Direction.OUT);
        assertThat(c.sort()).isEqualTo(TransactionSearchCondition.SortKey.AMOUNT);
        assertThat(c.minAmount()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(c.keyword()).isEqualTo("월세");
        assertThat(c.from()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
        assertThat(c.page()).isEqualTo(1);
        assertThat(c.size()).isEqualTo(10);
        // accountId는 컨트롤러가 채우지 않는다 (서비스가 소유권 검증 후 주입)
        assertThat(c.accountId()).isNull();
    }

    @Test
    @DisplayName("조건 없이 호출하면 기본값이 적용된다")
    void search_withoutParameters_appliesDefaults() throws Exception {
        given(transactionSearchService.search(anyString(), anyLong(), any()))
                .willReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/accounts/{no}/transactions/search", ACC)
                        .with(user(mockUser())))
                .andExpect(status().isOk());

        ArgumentCaptor<TransactionSearchCondition> captor =
                ArgumentCaptor.forClass(TransactionSearchCondition.class);
        verify(transactionSearchService).search(any(), any(), captor.capture());

        TransactionSearchCondition c = captor.getValue();
        assertThat(c.types()).isNull();
        assertThat(c.direction()).isNull();
        assertThat(c.page()).isZero();
        assertThat(c.size()).isEqualTo(20);
        // 정렬 키는 record 생성자가 기본값으로 채운다
        assertThat(c.sort()).isEqualTo(TransactionSearchCondition.SortKey.CREATED_AT);
    }

    @Test
    @DisplayName("검색 결과가 페이징 형태로 응답된다")
    void search_returnsPagedPayload() throws Exception {
        TransactionRow row = new TransactionRow(
                7L, Transaction.TransactionType.TRANSFER, Transaction.TransactionStatus.COMPLETED,
                new BigDecimal("300000"), "30260201000002", "전세금 이체",
                LocalDateTime.of(2026, 9, 1, 10, 0));

        given(transactionSearchService.search(anyString(), anyLong(), any()))
                .willReturn(PageResponse.of(List.of(row), 0, 20, 1));

        mockMvc.perform(get("/api/accounts/{no}/transactions/search", ACC)
                        .with(user(mockUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.content[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$.data.content[0].counterpartAccountNumber")
                        .value("30260201000002"));
    }

    @Test
    @DisplayName("알 수 없는 정렬 키는 400으로 거부된다")
    void search_unknownSortKey_isRejected() throws Exception {
        mockMvc.perform(get("/api/accounts/{no}/transactions/search", ACC)
                        .param("sort", "amount; DROP TABLE transactions")
                        .with(user(mockUser())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증 없이 검색하면 401이다")
    void search_withoutAuthentication_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/accounts/{no}/transactions/search", ACC))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("월별 요약이 연도 파라미터와 함께 호출된다")
    void monthlySummary_passesYear() throws Exception {
        given(transactionSearchService.monthlySummary(anyString(), anyLong(), anyInt()))
                .willReturn(List.of(new MonthlySummaryRow(
                        9, 5, new BigDecimal("120000"), new BigDecimal("330000"))));

        mockMvc.perform(get("/api/accounts/{no}/transactions/summary", ACC)
                        .param("year", "2026")
                        .with(user(mockUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].month").value(9))
                .andExpect(jsonPath("$.data[0].count").value(5))
                .andExpect(jsonPath("$.data[0].totalIn").value(120000))
                .andExpect(jsonPath("$.data[0].totalOut").value(330000));

        verify(transactionSearchService).monthlySummary(ACC, USER_ID, 2026);
    }

    @Test
    @DisplayName("연도 파라미터가 없으면 400이다")
    void monthlySummary_missingYear_isRejected() throws Exception {
        mockMvc.perform(get("/api/accounts/{no}/transactions/summary", ACC)
                        .with(user(mockUser())))
                .andExpect(status().isBadRequest());
    }
}
