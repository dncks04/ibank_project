package com.ibank.fraud;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.fraud.entity.FraudAlert;
import com.ibank.domain.fraud.exception.FraudAlertAlreadyResolvedException;
import com.ibank.domain.fraud.repository.FraudAlertRepository;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.ledger.service.ReconciliationService;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.domain.fraud.service.FraudReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 보류(HELD) 이체 검토·해제 워크플로우 검증.
 *
 * FDS로 보류된 이체를 운영자가 승인(release)하면 검토 시점 잔액·한도를 재검증해 실제로 자금이
 * 이동하고 복식부기 정합성이 유지되며, 반려(reject)하면 자금 이동 없이 거래가 취소되는지 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "ibank.fraud.enabled=true",
        // 신규 수취인 고액 룰만 트리거되도록 임계치를 낮추고 나머지 룰은 끈다
        "ibank.fraud.new-payee-high-value-min=1000",
        "ibank.fraud.velocity-max-outgoing=1000",
        "ibank.fraud.night-high-value-min=999999999"
})
class FraudReviewTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TransferService transferService;
    @Autowired FraudReviewService fraudReviewService;
    @Autowired AccountService accountService;
    @Autowired ReconciliationService reconciliationService;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired FraudAlertRepository fraudAlertRepository;

    private Long userId;
    private String fromAcc;
    private String toAcc;

    @BeforeEach
    void setUp() {
        fraudAlertRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(User.builder()
                .loginId("review-user").password("x").name("검토유저")
                .email("review@test.com").role(User.UserRole.ROLE_USER).build());
        userId = user.getId();
        fromAcc = accountService.openAccount(userId, new AccountOpenRequest(
                        UUID.randomUUID().toString(), new BigDecimal("10000000")))
                .accountNumber();
        toAcc = accountService.openAccount(userId, new AccountOpenRequest(
                        UUID.randomUUID().toString(), BigDecimal.ZERO))
                .accountNumber();
    }

    /** 신규 수취인 고액 이체를 보내 HELD 거래 + OPEN 경보를 만든다. */
    private FraudAlert createHeldTransfer(BigDecimal amount) {
        TransferResponse held = transferService.transfer(userId, new TransferRequest(
                UUID.randomUUID().toString(), fromAcc, toAcc, amount, "고액 신규 이체"));
        assertThat(held.status()).isEqualTo(Transaction.TransactionStatus.HELD);
        return fraudAlertRepository.findByStatusOrderByCreatedAtDesc(FraudAlert.Status.OPEN).getFirst();
    }

    @Test
    @DisplayName("승인하면 자금이 이동하고 거래는 완료·경보는 처리되며 정합성이 유지된다")
    void release_movesFundsAndKeepsLedgerConsistent() {
        BigDecimal amount = new BigDecimal("5000");
        FraudAlert alert = createHeldTransfer(amount);

        TransferResponse result = fraudReviewService.release(alert.getId());

        assertThat(result.status()).isEqualTo(Transaction.TransactionStatus.COMPLETED);
        assertThat(accountRepository.findByAccountNumber(fromAcc).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("9995000"));
        assertThat(accountRepository.findByAccountNumber(toAcc).orElseThrow().getBalance())
                .isEqualByComparingTo(amount);

        // 경보 처리 완료
        assertThat(fraudAlertRepository.findById(alert.getId()).orElseThrow().getStatus())
                .isEqualTo(FraudAlert.Status.RESOLVED);
        assertThat(fraudAlertRepository.findByStatusOrderByCreatedAtDesc(FraudAlert.Status.OPEN)).isEmpty();

        // 복식부기 정합성: 잔액==원장 합, 모든 분개 균형, 시산표 0
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();
        assertThat(reconciliationService.findUnbalancedJournals()).isEmpty();
        assertThat(reconciliationService.trialBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("반려하면 자금 이동 없이 거래가 취소되고 경보가 처리된다")
    void reject_cancelsTransferWithoutMovingFunds() {
        BigDecimal amount = new BigDecimal("5000");
        FraudAlert alert = createHeldTransfer(amount);
        long ledgerCountBefore = ledgerEntryRepository.count();

        TransferResponse result = fraudReviewService.reject(alert.getId());

        assertThat(result.status()).isEqualTo(Transaction.TransactionStatus.CANCELLED);
        assertThat(accountRepository.findByAccountNumber(fromAcc).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("10000000"));
        assertThat(accountRepository.findByAccountNumber(toAcc).orElseThrow().getBalance())
                .isEqualByComparingTo(BigDecimal.ZERO);

        // 원장 항목 미생성 (잔액 불변)
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerCountBefore);
        assertThat(fraudAlertRepository.findById(alert.getId()).orElseThrow().getStatus())
                .isEqualTo(FraudAlert.Status.RESOLVED);
    }

    @Test
    @DisplayName("이미 처리된 경보는 더 이상 검토할 수 없다")
    void release_afterReject_isRejected() {
        FraudAlert alert = createHeldTransfer(new BigDecimal("5000"));
        fraudReviewService.reject(alert.getId());

        // 경보가 이미 RESOLVED이므로 자금 이동 전에 즉시 거부된다 (409)
        assertThatThrownBy(() -> fraudReviewService.release(alert.getId()))
                .isInstanceOf(FraudAlertAlreadyResolvedException.class);
    }

    @Test
    @DisplayName("같은 경보를 동시에 승인·반려해도 단 한 번만 처리된다 (이중 처리 방지)")
    void concurrentReleaseAndReject_isProcessedExactlyOnce() throws Exception {
        BigDecimal amount = new BigDecimal("5000");
        FraudAlert alert = createHeldTransfer(amount);
        Long alertId = alert.getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Callable<Throwable> release = () -> attempt(ready, go, () -> fraudReviewService.release(alertId));
        Callable<Throwable> reject = () -> attempt(ready, go, () -> fraudReviewService.reject(alertId));

        Future<Throwable> f1 = pool.submit(release);
        Future<Throwable> f2 = pool.submit(reject);
        ready.await();
        go.countDown(); // 두 스레드를 동시에 출발
        Throwable e1 = f1.get();
        Throwable e2 = f2.get();
        pool.shutdown();

        // 정확히 하나만 성공하고, 나머지는 이미 처리됨(409)으로 거부되어야 한다
        long failures = Stream.of(e1, e2).filter(t -> t != null).count();
        assertThat(failures).isEqualTo(1);
        Stream.of(e1, e2).filter(t -> t != null).forEach(t ->
                assertThat(t).isInstanceOf(FraudAlertAlreadyResolvedException.class));

        // 경보는 한 번만 처리(RESOLVED), 거래는 COMPLETED 또는 CANCELLED 중 하나로만 확정
        assertThat(fraudAlertRepository.findById(alertId).orElseThrow().getStatus())
                .isEqualTo(FraudAlert.Status.RESOLVED);
        Transaction.TransactionStatus txStatus =
                transactionRepository.findById(alert.getTransactionId()).orElseThrow().getStatus();
        assertThat(txStatus).isIn(
                Transaction.TransactionStatus.COMPLETED, Transaction.TransactionStatus.CANCELLED);

        // 어느 쪽이 이겼든 복식부기 정합성은 유지된다
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();
        assertThat(reconciliationService.findUnbalancedJournals()).isEmpty();
        assertThat(reconciliationService.trialBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** 두 스레드가 동시에 출발하도록 정렬한 뒤 작업을 실행하고, 발생한 예외(없으면 null)를 반환한다. */
    private Throwable attempt(CountDownLatch ready, CountDownLatch go, Runnable action) {
        ready.countDown();
        try {
            go.await();
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @Test
    @DisplayName("승인 시점에 잔액이 부족하면 롤백되어 경보는 OPEN으로 남는다")
    void release_insufficientBalance_keepsAlertOpen() {
        FraudAlert alert = createHeldTransfer(new BigDecimal("5000"));

        // 보류 사이에 잔액을 정상 출금으로 소진 → 검토 시점 잔액 < 이체 금액
        transferService.withdraw(userId, new WithdrawRequest(
                fromAcc, new BigDecimal("9999000"), UUID.randomUUID().toString(), "잔액 소진"));

        assertThatThrownBy(() -> fraudReviewService.release(alert.getId()))
                .isInstanceOf(com.ibank.domain.account.entity.InsufficientBalanceException.class);

        // 롤백: 거래는 여전히 HELD, 경보는 여전히 OPEN (재검토/반려 가능)
        assertThat(transactionRepository.findById(alert.getTransactionId()).orElseThrow().getStatus())
                .isEqualTo(Transaction.TransactionStatus.HELD);
        assertThat(fraudAlertRepository.findById(alert.getId()).orElseThrow().getStatus())
                .isEqualTo(FraudAlert.Status.OPEN);
    }
}
