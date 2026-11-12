package com.ibank.transaction;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.decision.FallbackDecisionPolicy;
import com.ibank.domain.decision.FraudDecisionPort.Decision;
import com.ibank.domain.decision.FraudDecisionPort.Source;
import com.ibank.domain.decision.FraudDecisionUnavailableException;
import com.ibank.domain.hold.entity.AccountHold;
import com.ibank.domain.hold.repository.AccountHoldRepository;
import com.ibank.domain.ledger.service.ReconciliationService;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.entity.Transaction.TransactionStatus;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.PendingReviewRecoveryService;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 판정 도중 처리가 끊겨 PENDING_REVIEW로 남은 거래의 복구.
 *
 * <p>판정 포트가 예기치 못한 예외를 던지게 해 "가승인만 커밋되고 끝난 거래"를 만든 뒤,
 * 복구가 그 거래를 재판정해 확정하는지 본다. 복구 대상은 가승인 뒤 1분(기본값)이 지난 거래뿐이므로
 * 요청 시각을 과거로 돌려 오래된 거래로 만든다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PendingReviewRecoveryTest {

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
    @Autowired private ScriptedFraudDecisionPort fds;
    @Autowired private TransferService transferService;
    @Autowired private PendingReviewRecoveryService recoveryService;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountHoldRepository holdRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final BigDecimal INITIAL = new BigDecimal("1000000");

    private Long userId;
    private String accA;
    private String accB;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        fds.reset();
        userId = saveUser("recovery-user", LocalDate.of(1990, 1, 1));
        accA = open(userId, INITIAL);
        accB = open(userId, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("오래된 판정 대기 거래를 재판정해 승인이면 완료한다")
    void staleOrphan_approved_completes() {
        Long orphan = createOrphanTransfer("300000");
        makeStale(orphan);
        fds.approveAll();

        int recovered = recoveryService.recoverStale();

        assertThat(recovered).isEqualTo(1);
        Transaction tx = transactionRepository.findById(orphan).orElseThrow();
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(tx.getDecision().getSource()).isEqualTo(Source.FDS);
        assertThat(holdStatusOf(orphan)).isEqualTo(AccountHold.Status.CONSUMED);
        assertThat(balanceOf(accA)).isEqualByComparingTo("700000");
        assertThat(balanceOf(accB)).isEqualByComparingTo("300000");
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();
        assertThat(reconciliationService.trialBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // 재판정 요청에는 원래 거래의 식별자가 그대로 실린다 (FDS 서비스가 같은 거래임을 알 수 있다)
        assertThat(fds.requests()).last().satisfies(request -> {
            assertThat(request.transactionId()).isEqualTo(orphan);
            assertThat(request.idempotencyKey()).isEqualTo(tx.getIdempotencyKey());
            assertThat(request.fromAccountNumber()).isEqualTo(accA);
            assertThat(request.toAccountNumber()).isEqualTo(accB);
            assertThat(request.customerId()).isEqualTo(userId);
        });
    }

    @Test
    @DisplayName("재판정이 보류면 HELD로 넘기고 금액은 계속 묶어 둔다")
    void staleOrphan_held() {
        Long orphan = createOrphanTransfer("300000");
        makeStale(orphan);
        fds.holdAll("NEW_PAYEE_HIGH_VALUE");

        recoveryService.recoverStale();

        assertThat(statusOf(orphan)).isEqualTo(TransactionStatus.HELD);
        assertThat(holdStatusOf(orphan)).isEqualTo(AccountHold.Status.ACTIVE);
        assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL);
    }

    @Test
    @DisplayName("방금 가승인된 거래는 건드리지 않는다 (처리 중인 요청을 가로채지 않는다)")
    void freshPendingReview_untouched() {
        Long fresh = createOrphanTransfer("300000"); // 요청 시각이 방금이라 복구 대상이 아니다
        fds.approveAll();
        int callsBefore = fds.requests().size();

        int recovered = recoveryService.recoverStale();

        assertThat(recovered).isZero();
        assertThat(statusOf(fresh)).isEqualTo(TransactionStatus.PENDING_REVIEW);
        assertThat(fds.requests()).hasSize(callsBefore);
    }

    @Test
    @DisplayName("복구 시점에도 FDS가 응답하지 않으면 대체 정책으로 확정한다 (고령 고객은 보류, 그 외는 완료)")
    void staleOrphan_fdsStillDown_fallbackDecides() {
        Long elder = saveUser("recovery-elder", LocalDate.of(1950, 1, 1));
        String elderAccount = open(elder, INITIAL);

        Long youngOrphan = createOrphanTransfer("300000");
        fds.failWith(new IllegalStateException("판정 도중 중단"));
        TransferRequest elderRequest = new TransferRequest(
                UUID.randomUUID().toString(), elderAccount, accB, new BigDecimal("200000"), "고령 고객 이체");
        assertThatThrownBy(() -> transferService.transfer(elder, elderRequest))
                .isInstanceOf(IllegalStateException.class);
        Long elderOrphan = transactionRepository.findByIdempotencyKey(elderRequest.idempotencyKey())
                .orElseThrow().getId();
        makeStale(youngOrphan);
        makeStale(elderOrphan);

        fds.failWith(new FraudDecisionUnavailableException("FDS 서비스 응답 없음"));
        int recovered = recoveryService.recoverStale();

        assertThat(recovered).isEqualTo(2);
        assertThat(statusOf(youngOrphan)).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(statusOf(elderOrphan)).isEqualTo(TransactionStatus.HELD);
        Transaction elderTx = transactionRepository.findById(elderOrphan).orElseThrow();
        assertThat(elderTx.getDecision().getSource()).isEqualTo(Source.FALLBACK);
        assertThat(elderTx.getDecision().reasonCodeList())
                .containsExactly(FallbackDecisionPolicy.REASON_FDS_UNAVAILABLE);
    }

    @Test
    @DisplayName("한 건의 복구가 실패해도 나머지는 복구되고, 실패한 건은 다음 실행에서 다시 시도된다")
    void oneFailure_doesNotBlockOthers() {
        Long first = createOrphanTransfer("100000");
        Long second = createOrphanTransfer("200000");
        Long third = createOrphanWithdrawal("50000");
        makeStale(first);
        makeStale(second);
        makeStale(third);

        // 두 번째 거래의 재판정만 예기치 못한 예외로 실패하게 한다
        fds.decideBy(request -> {
            if (request.transactionId().equals(second)) {
                throw new IllegalStateException("이 거래만 재판정 실패");
            }
            return Decision.approve(Source.FDS);
        });

        assertThat(recoveryService.recoverStale()).isEqualTo(2);
        assertThat(statusOf(first)).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(statusOf(second)).isEqualTo(TransactionStatus.PENDING_REVIEW);
        assertThat(statusOf(third)).isEqualTo(TransactionStatus.COMPLETED);

        // 다음 실행: 남은 한 건만 대상이 되고 이번에는 성공한다
        fds.approveAll();
        assertThat(recoveryService.recoverStale()).isEqualTo(1);
        assertThat(statusOf(second)).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(recoveryService.recoverStale()).isZero();

        assertThat(balanceOf(accA)).isEqualByComparingTo("650000");
        assertThat(balanceOf(accB)).isEqualByComparingTo("300000");
        assertThat(holdRepository.findAll()).allMatch(h -> h.getStatus() == AccountHold.Status.CONSUMED);
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();
        assertThat(reconciliationService.trialBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("복구 시점에 실행 조건이 깨져 있으면 FAILED로 확정하고 묶인 금액을 푼다")
    void staleOrphan_revalidationFails_releasesHold() {
        Long orphan = createOrphanTransfer("300000");
        makeStale(orphan);
        accountService.closeAccount(accB, userId, UUID.randomUUID().toString()); // 입금 계좌 해지
        fds.approveAll();

        assertThat(recoveryService.recoverStale()).isEqualTo(1);

        Transaction tx = transactionRepository.findById(orphan).orElseThrow();
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.getFailureCode()).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat(holdStatusOf(orphan)).isEqualTo(AccountHold.Status.RELEASED);
        assertThat(balanceOf(accA)).isEqualByComparingTo(INITIAL);
    }

    // ---- 헬퍼 ----

    /** 판정 단계에서 예기치 못한 예외가 나게 해, 가승인만 커밋된 거래를 만든다. */
    private Long createOrphanTransfer(String amount) {
        fds.failWith(new IllegalStateException("판정 도중 중단"));
        TransferRequest request = new TransferRequest(
                UUID.randomUUID().toString(), accA, accB, new BigDecimal(amount), "복구 테스트");
        assertThatThrownBy(() -> transferService.transfer(userId, request))
                .isInstanceOf(IllegalStateException.class);
        Long id = transactionRepository.findByIdempotencyKey(request.idempotencyKey()).orElseThrow().getId();
        assertThat(statusOf(id)).isEqualTo(TransactionStatus.PENDING_REVIEW);
        return id;
    }

    private Long createOrphanWithdrawal(String amount) {
        fds.failWith(new IllegalStateException("판정 도중 중단"));
        WithdrawRequest request = new WithdrawRequest(
                accA, new BigDecimal(amount), UUID.randomUUID().toString(), "복구 테스트");
        assertThatThrownBy(() -> transferService.withdraw(userId, request))
                .isInstanceOf(IllegalStateException.class);
        return transactionRepository.findByIdempotencyKey(request.idempotencyKey()).orElseThrow().getId();
    }

    /** 요청 시각을 10분 전으로 돌려 복구 대상(가승인 뒤 1분 경과)으로 만든다. */
    private void makeStale(Long transactionId) {
        jdbcTemplate.update(
                "UPDATE transactions SET created_at = created_at - INTERVAL '10 minutes' WHERE id = ?", transactionId);
    }

    private Long saveUser(String loginId, LocalDate birthDate) {
        return userRepository.save(User.builder()
                .loginId(loginId).password("x").name("복구유저")
                .email(loginId + "@recovery.test").birthDate(birthDate)
                .role(User.UserRole.ROLE_USER).build()).getId();
    }

    private String open(Long ownerId, BigDecimal initialBalance) {
        return accountService.openAccount(ownerId,
                new AccountOpenRequest(UUID.randomUUID().toString(), initialBalance)).accountNumber();
    }

    private TransactionStatus statusOf(Long transactionId) {
        return transactionRepository.findById(transactionId).orElseThrow().getStatus();
    }

    private AccountHold.Status holdStatusOf(Long transactionId) {
        return holdRepository.findByTransactionId(transactionId).orElseThrow().getStatus();
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }
}
