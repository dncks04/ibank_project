package com.ibank.search;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.transaction.dto.DepositRequest;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.search.MonthlySummaryRow;
import com.ibank.domain.transaction.search.TransactionRow;
import com.ibank.domain.transaction.search.TransactionSearchCondition;
import com.ibank.domain.transaction.search.TransactionSearchService;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.global.response.PageResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MyBatis 동적 조회 통합 테스트.
 *
 * <p>동적 SQL은 조건 조합에 따라 문장이 달라지므로, 조합별로 실제 PostgreSQL에 던져 봐야
 * 맞는지 알 수 있다. 기존 동시성 테스트와 같이 Testcontainers로 실제 DB를 띄운다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TransactionSearchTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private TransactionSearchService searchService;
    @Autowired private TransferService transferService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String ACC_A = "30260201000001";
    private static final String ACC_B = "30260201000002";

    private Long userAId;
    private Long userBId;

    @BeforeEach
    void setUp() {
        clearAll();

        User userA = userRepository.save(User.builder()
                .loginId("search-user-a").password(passwordEncoder.encode("pass"))
                .name("사용자A").email("searchA@test.com")
                .role(User.UserRole.ROLE_USER).build());
        userAId = userA.getId();

        User userB = userRepository.save(User.builder()
                .loginId("search-user-b").password(passwordEncoder.encode("pass"))
                .name("사용자B").email("searchB@test.com")
                .role(User.UserRole.ROLE_USER).build());
        userBId = userB.getId();

        accountRepository.save(Account.builder()
                .accountNumber(ACC_A).owner(userA)
                .initialBalance(new BigDecimal("1000000")).build());
        accountRepository.save(Account.builder()
                .accountNumber(ACC_B).owner(userB)
                .initialBalance(new BigDecimal("1000000")).build());
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    private void clearAll() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    /** A 계좌에 입금 1건, 출금 1건, B로 이체 2건, B에서 받는 이체 1건을 만든다. */
    private void seedMixedTransactions() {
        transferService.depositForUser(userAId, new DepositRequest(
                ACC_A, new BigDecimal("50000"), UUID.randomUUID().toString(), "급여 입금"));
        transferService.withdraw(userAId, new WithdrawRequest(
                ACC_A, new BigDecimal("20000"), UUID.randomUUID().toString(), "현금 출금"));
        transferService.transfer(userAId, new TransferRequest(
                UUID.randomUUID().toString(), ACC_A, ACC_B, new BigDecimal("10000"), "월세 이체"));
        transferService.transfer(userAId, new TransferRequest(
                UUID.randomUUID().toString(), ACC_A, ACC_B, new BigDecimal("300000"), "전세금 이체"));
        transferService.transfer(userBId, new TransferRequest(
                UUID.randomUUID().toString(), ACC_B, ACC_A, new BigDecimal("70000"), "정산 입금"));
    }

    private TransactionSearchCondition condition() {
        return new TransactionSearchCondition(
                null, null, null, null, null, null, null, null, null, null, 0, 20);
    }

    private PageResponse<TransactionRow> search(TransactionSearchCondition c) {
        return searchService.search(ACC_A, userAId, c);
    }

    @Test
    @DisplayName("조건을 주지 않으면 해당 계좌의 모든 거래가 조회된다")
    void search_noCondition_returnsAllForAccount() {
        seedMixedTransactions();

        PageResponse<TransactionRow> result = search(condition());

        // A가 관여한 거래 5건 전부 (입금·출금·보낸이체 2·받은이체 1)
        assertThat(result.totalElements()).isEqualTo(5);
        assertThat(result.content()).hasSize(5);
    }

    @Test
    @DisplayName("유형·금액대·방향을 함께 걸면 교집합만 조회된다")
    void search_combinedFilters_returnsIntersection() {
        seedMixedTransactions();

        TransactionSearchCondition c = new TransactionSearchCondition(
                null, null, null,
                List.of(Transaction.TransactionType.TRANSFER),
                List.of(Transaction.TransactionStatus.COMPLETED),
                new BigDecimal("100000"), null,
                TransactionSearchCondition.Direction.OUT,
                null, null, 0, 20);

        PageResponse<TransactionRow> result = search(c);

        // TRANSFER + 10만원 이상 + A가 출금 → 전세금 이체 30만원 1건
        assertThat(result.totalElements()).isEqualTo(1);
        TransactionRow row = result.content().get(0);
        assertThat(row.amount()).isEqualByComparingTo(new BigDecimal("300000"));
        assertThat(row.description()).isEqualTo("전세금 이체");
        assertThat(row.counterpartAccountNumber()).isEqualTo(ACC_B);
    }

    @Test
    @DisplayName("방향 IN은 들어온 거래만, OUT은 나간 거래만 조회한다")
    void search_directionFilter_separatesInAndOut() {
        seedMixedTransactions();

        PageResponse<TransactionRow> in = search(new TransactionSearchCondition(
                null, null, null, null, null, null, null,
                TransactionSearchCondition.Direction.IN, null, null, 0, 20));
        PageResponse<TransactionRow> out = search(new TransactionSearchCondition(
                null, null, null, null, null, null, null,
                TransactionSearchCondition.Direction.OUT, null, null, 0, 20));

        // 들어온 것: 입금 5만, B에서 온 이체 7만
        assertThat(in.totalElements()).isEqualTo(2);
        // 나간 것: 출금 2만, 이체 1만·30만
        assertThat(out.totalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("키워드는 description 부분 일치로 걸린다")
    void search_keyword_matchesPartialDescription() {
        seedMixedTransactions();

        PageResponse<TransactionRow> result = search(new TransactionSearchCondition(
                null, null, null, null, null, null, null, null, "이체", null, 0, 20));

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.content())
                .extracting(TransactionRow::description)
                .containsExactlyInAnyOrder("월세 이체", "전세금 이체");
    }

    @Test
    @DisplayName("유형 IN 조건에 여러 값을 주면 그중 하나라도 맞는 거래가 조회된다")
    void search_multipleTypes_appliesInClause() {
        seedMixedTransactions();

        PageResponse<TransactionRow> result = search(new TransactionSearchCondition(
                null, null, null,
                List.of(Transaction.TransactionType.DEPOSIT, Transaction.TransactionType.WITHDRAWAL),
                null, null, null, null, null, null, 0, 20));

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.content())
                .extracting(TransactionRow::type)
                .containsExactlyInAnyOrder(
                        Transaction.TransactionType.DEPOSIT,
                        Transaction.TransactionType.WITHDRAWAL);
    }

    @Test
    @DisplayName("금액 정렬을 지정하면 큰 금액부터 조회된다")
    void search_sortByAmount_ordersDescending() {
        seedMixedTransactions();

        PageResponse<TransactionRow> result = search(new TransactionSearchCondition(
                null, null, null, null, null, null, null, null, null,
                TransactionSearchCondition.SortKey.AMOUNT, 0, 20));

        assertThat(result.content())
                .extracting(TransactionRow::amount)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(result.content().get(0).amount()).isEqualByComparingTo(new BigDecimal("300000"));
    }

    @Test
    @DisplayName("기본 정렬은 최신순이다")
    void search_defaultSort_ordersByCreatedAtDesc() {
        seedMixedTransactions();

        PageResponse<TransactionRow> result = search(condition());

        assertThat(result.content())
                .extracting(TransactionRow::createdAt)
                .isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    @DisplayName("페이징 경계에서 총건수는 유지되고 마지막 페이지만 짧게 채워진다")
    void search_paging_keepsTotalAndFillsLastPagePartially() {
        seedMixedTransactions();

        PageResponse<TransactionRow> page0 = search(new TransactionSearchCondition(
                null, null, null, null, null, null, null, null, null, null, 0, 2));
        PageResponse<TransactionRow> page2 = search(new TransactionSearchCondition(
                null, null, null, null, null, null, null, null, null, null, 2, 2));

        assertThat(page0.content()).hasSize(2);
        assertThat(page0.totalElements()).isEqualTo(5);
        assertThat(page0.totalPages()).isEqualTo(3);

        // 5건을 2개씩 나누면 마지막(3번째) 페이지는 1건
        assertThat(page2.content()).hasSize(1);
        assertThat(page2.totalElements()).isEqualTo(5);

        // 페이지가 겹치지 않는다
        assertThat(page0.content().get(0).id()).isNotEqualTo(page2.content().get(0).id());
    }

    @Test
    @DisplayName("총건수와 목록이 같은 조건으로 계산된다")
    void search_countAndContent_agreeUnderSameCondition() {
        seedMixedTransactions();

        TransactionSearchCondition c = new TransactionSearchCondition(
                null, null, null,
                List.of(Transaction.TransactionType.TRANSFER),
                null, null, null, null, null, null, 0, 100);

        PageResponse<TransactionRow> result = search(c);

        // 한 페이지에 다 담기므로 총건수와 실제 행 수가 같아야 한다
        assertThat(result.content()).hasSize((int) result.totalElements());
        assertThat(result.totalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("기간 밖 조건이면 아무것도 조회되지 않는다")
    void search_dateRangeOutside_returnsEmpty() {
        seedMixedTransactions();

        LocalDateTime longAgo = LocalDate.now().minusDays(30).atStartOfDay();
        PageResponse<TransactionRow> result = search(new TransactionSearchCondition(
                null, longAgo.minusDays(10), longAgo, null, null, null, null,
                null, null, null, 0, 20));

        assertThat(result.totalElements()).isZero();
        assertThat(result.content()).isEmpty();
    }

    @Test
    @DisplayName("상대 계좌번호가 SQL 조인으로 채워진다")
    void search_counterpartAccountNumber_isResolvedInSql() {
        transferService.transfer(userAId, new TransferRequest(
                UUID.randomUUID().toString(), ACC_A, ACC_B, new BigDecimal("5000"), "상대 확인"));

        // 보낸 쪽에서 보면 상대는 B
        assertThat(search(condition()).content().get(0).counterpartAccountNumber()).isEqualTo(ACC_B);

        // 받은 쪽에서 보면 상대는 A
        PageResponse<TransactionRow> fromB = searchService.search(ACC_B, userBId, condition());
        assertThat(fromB.content().get(0).counterpartAccountNumber()).isEqualTo(ACC_A);
    }

    @Test
    @DisplayName("입금·출금은 상대 계좌가 없으므로 null로 조회된다")
    void search_depositAndWithdrawal_haveNullCounterpart() {
        transferService.depositForUser(userAId, new DepositRequest(
                ACC_A, new BigDecimal("10000"), UUID.randomUUID().toString(), "입금"));
        transferService.withdraw(userAId, new WithdrawRequest(
                ACC_A, new BigDecimal("5000"), UUID.randomUUID().toString(), "출금"));

        PageResponse<TransactionRow> result = search(condition());

        assertThat(result.content())
                .extracting(TransactionRow::counterpartAccountNumber)
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("타인 계좌를 검색하면 접근이 거부된다")
    void search_otherUsersAccount_isDenied() {
        assertThatThrownBy(() -> searchService.search(ACC_A, userBId, condition()))
                .isInstanceOf(AccountAccessDeniedException.class);
    }

    @Test
    @DisplayName("월별 요약이 입금·출금 합계와 건수를 집계한다")
    void monthlySummary_aggregatesInAndOut() {
        seedMixedTransactions();

        int year = LocalDate.now().getYear();
        List<MonthlySummaryRow> summary = searchService.monthlySummary(ACC_A, userAId, year);

        assertThat(summary).hasSize(1);
        MonthlySummaryRow row = summary.get(0);

        assertThat(row.month()).isEqualTo(LocalDate.now().getMonthValue());
        assertThat(row.count()).isEqualTo(5);
        // 들어온 돈: 입금 50,000 + B에서 온 이체 70,000
        assertThat(row.totalIn()).isEqualByComparingTo(new BigDecimal("120000"));
        // 나간 돈: 출금 20,000 + 이체 10,000 + 300,000
        assertThat(row.totalOut()).isEqualByComparingTo(new BigDecimal("330000"));
    }

    @Test
    @DisplayName("거래가 없는 해를 조회하면 빈 결과가 나온다")
    void monthlySummary_yearWithoutTransactions_isEmpty() {
        seedMixedTransactions();

        List<MonthlySummaryRow> summary =
                searchService.monthlySummary(ACC_A, userAId, LocalDate.now().getYear() - 5);

        assertThat(summary).isEmpty();
    }

    @Test
    @DisplayName("타인 계좌의 월별 요약도 접근이 거부된다")
    void monthlySummary_otherUsersAccount_isDenied() {
        assertThatThrownBy(() ->
                searchService.monthlySummary(ACC_A, userBId, LocalDate.now().getYear()))
                .isInstanceOf(AccountAccessDeniedException.class);
    }
}
