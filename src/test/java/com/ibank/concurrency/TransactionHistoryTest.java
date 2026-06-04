package com.ibank.concurrency;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.transaction.dto.TransactionHistoryResponse;
import com.ibank.domain.transaction.dto.TransactionSearchRequest;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransactionQueryService;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TransactionHistoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private TransactionQueryService queryService;
    @Autowired private TransferService transferService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String ACC_A = "30260101000001";
    private static final String ACC_B = "30260101000002";

    private Long userAId;
    private Long userBId;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        User userA = userRepository.save(User.builder()
                .loginId("history-user-a").password(passwordEncoder.encode("pass"))
                .name("사용자A").email("historyA@test.com")
                .role(User.UserRole.ROLE_USER).build());
        userAId = userA.getId();

        User userB = userRepository.save(User.builder()
                .loginId("history-user-b").password(passwordEncoder.encode("pass"))
                .name("사용자B").email("historyB@test.com")
                .role(User.UserRole.ROLE_USER).build());
        userBId = userB.getId();

        accountRepository.save(Account.builder()
                .accountNumber(ACC_A).owner(userA)
                .initialBalance(new BigDecimal("100000")).build());
        accountRepository.save(Account.builder()
                .accountNumber(ACC_B).owner(userB)
                .initialBalance(BigDecimal.ZERO).build());
    }

    @AfterEach
    void tearDown() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("이체 후 송금 계좌 거래 내역에 TRANSFER 타입으로 조회된다")
    void history_afterTransfer_appearsInSenderHistory() {
        transferService.transfer(new TransferRequest(
                UUID.randomUUID().toString(), ACC_A, ACC_B,
                new BigDecimal("5000"), "테스트 이체"));

        Page<TransactionHistoryResponse> result = queryService.getHistory(
                ACC_A, userAId, TransactionSearchRequest.of(null, null, 0, 20));

        assertThat(result.getTotalElements()).isEqualTo(1);
        TransactionHistoryResponse tx = result.getContent().get(0);
        assertThat(tx.type()).isEqualTo(Transaction.TransactionType.TRANSFER);
        assertThat(tx.amount()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(tx.counterpartAccountNumber()).isEqualTo(ACC_B);
        assertThat(tx.status()).isEqualTo(Transaction.TransactionStatus.COMPLETED);
    }

    @Test
    @DisplayName("이체 후 수신 계좌 거래 내역에도 동일 거래가 조회된다")
    void history_afterTransfer_appearsInReceiverHistory() {
        transferService.transfer(new TransferRequest(
                UUID.randomUUID().toString(), ACC_A, ACC_B,
                new BigDecimal("3000"), "수신 테스트"));

        Page<TransactionHistoryResponse> result = queryService.getHistory(
                ACC_B, userBId, TransactionSearchRequest.of(null, null, 0, 20));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).counterpartAccountNumber()).isEqualTo(ACC_A);
    }

    @Test
    @DisplayName("여러 건 이체 후 최신순으로 페이징된다")
    void history_multipleTransfers_orderedByCreatedAtDesc() {
        for (int i = 0; i < 5; i++) {
            transferService.transfer(new TransferRequest(
                    UUID.randomUUID().toString(), ACC_A, ACC_B,
                    new BigDecimal("1000"), "이체 " + i));
        }

        Page<TransactionHistoryResponse> page0 = queryService.getHistory(
                ACC_A, userAId, TransactionSearchRequest.of(null, null, 0, 3));
        Page<TransactionHistoryResponse> page1 = queryService.getHistory(
                ACC_A, userAId, TransactionSearchRequest.of(null, null, 1, 3));

        assertThat(page0.getTotalElements()).isEqualTo(5);
        assertThat(page0.getContent()).hasSize(3);
        assertThat(page1.getContent()).hasSize(2);

        // 최신순 확인
        var times = page0.getContent().stream().map(TransactionHistoryResponse::createdAt).toList();
        assertThat(times).isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    @DisplayName("날짜 범위 필터가 적용된다")
    void history_dateRangeFilter_appliedCorrectly() {
        transferService.transfer(new TransferRequest(
                UUID.randomUUID().toString(), ACC_A, ACC_B,
                new BigDecimal("1000"), "날짜 필터 테스트"));

        LocalDate today = LocalDate.now();

        // 오늘 날짜 범위 → 1건 조회
        Page<TransactionHistoryResponse> inRange = queryService.getHistory(
                ACC_A, userAId, TransactionSearchRequest.of(today, today, 0, 20));
        assertThat(inRange.getTotalElements()).isEqualTo(1);

        // 어제까지만 → 0건
        Page<TransactionHistoryResponse> outOfRange = queryService.getHistory(
                ACC_A, userAId, TransactionSearchRequest.of(today.minusDays(7), today.minusDays(1), 0, 20));
        assertThat(outOfRange.getTotalElements()).isEqualTo(0);
    }

    @Test
    @DisplayName("타인 계좌 거래 내역 조회 시 403 예외가 발생한다")
    void history_otherUserAccount_throwsForbidden() {
        assertThatThrownBy(() ->
            queryService.getHistory(ACC_A, userBId,
                    TransactionSearchRequest.of(null, null, 0, 20))
        ).isInstanceOf(com.ibank.domain.account.service.AccountAccessDeniedException.class);
    }

    @Test
    @DisplayName("거래가 없는 계좌는 빈 페이지를 반환한다")
    void history_noTransactions_returnsEmptyPage() {
        Page<TransactionHistoryResponse> result = queryService.getHistory(
                ACC_A, userAId, TransactionSearchRequest.of(null, null, 0, 20));

        assertThat(result.getTotalElements()).isEqualTo(0);
        assertThat(result.getContent()).isEmpty();
    }
}
