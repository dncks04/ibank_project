package com.ibank.concurrency;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.ledger.service.ReconciliationService;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.repository.TransactionRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 커넥션 풀 포화 상황의 복원력 검증.
 *
 * <p>{@link TransferConcurrencyTest}가 풀 안에서의 경합 정확성을 보는 것과 달리, 여기서는 풀 크기의
 * 여러 배를 한꺼번에 던져 커넥션 획득 실패를 일부러 만든다. 확인하려는 것은 "실패하지 않는다"가 아니라
 * <b>실패하는 방식</b>이다. 자원이 모자라 거부된 이체는 자금을 건드리지 않고 끝나야 한다.
 *
 * <p>커넥션 획득 타임아웃은 {@code JpaTransactionManager.doBegin()}에서 트랜잭션이 시작되기도 전에
 * 발생하므로 SQL이 한 줄도 실행되지 않는다. 따라서 실패 건수와 무관하게 잔액과 원장은 온전해야 한다.
 *
 * <p>허용하는 실패는 커넥션 획득 타임아웃 하나뿐이다. 행 락 타임아웃이나 낙관적 락 충돌, 무결성 위반이
 * 섞이면 그것은 자원 문제가 아니라 데드락 방지나 정합성에 구멍이 생겼다는 뜻이므로 하드 실패로 다룬다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TransferPoolSaturationInvariantTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private TransferService transferService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountService accountService;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final BigDecimal INITIAL_EACH = new BigDecimal("50000");
    private static final BigDecimal TOTAL = new BigDecimal("100000");

    /** 커넥션 풀은 20이다. 그 여러 배를 던져 획득 실패를 확실히 만든다. */
    private static final int PAIRS = 150;

    private Long userId;
    private String accA;
    private String accB;

    @BeforeEach
    void setUp() {
        clearAll();

        User user = userRepository.save(User.builder()
                .loginId("saturation-user").password(passwordEncoder.encode("pass"))
                .name("포화테스트").email("saturation@test.com")
                .role(User.UserRole.ROLE_USER).build());
        userId = user.getId();

        // 정식 개설 경로로 만든다. accountRepository.save()로 만들면 초기 잔액의 원장 leg가 남지 않아
        // "잔액 == 원장 합계" 검사가 실제와 무관한 불일치를 낸다.
        accA = openAccount();
        accB = openAccount();
    }

    @AfterEach
    void tearDown() {
        clearAll();
    }

    @Test
    @DisplayName("커넥션 풀 포화 - 실패한 이체는 자금을 건드리지 않는다")
    void poolSaturation_failedTransfersLeaveMoneyUntouched() throws InterruptedException {
        BigDecimal amount = new BigDecimal("100");
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(PAIRS * 2);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(PAIRS * 2);
        for (int i = 0; i < PAIRS; i++) {
            executor.submit(() -> runTransfer(accA, accB, amount, errors, startLatch, doneLatch));
            executor.submit(() -> runTransfer(accB, accA, amount, errors, startLatch, doneLatch));
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(120, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).as("모든 요청이 제한 시간 내 끝나야 한다 (데드락 없음)").isTrue();

        // 실패 시 원인을 좁힐 수 있도록 수치를 남긴다. 판정은 아래 단언이 한다.
        logOutcome(errors);

        // 정합성 단언이 먼저다. 오류 검사를 앞에 두면 실패가 생겼을 때 여기서 멈춰,
        // 정작 확인해야 할 잔액과 원장이 검사되지 않는다.
        assertInvariantsHold();

        // 허용되는 실패는 커넥션 획득 타임아웃뿐이다. 그 외에는 자원 문제가 아니다.
        List<Throwable> disallowed = errors.stream()
                .filter(t -> !isConnectionAcquisitionTimeout(t))
                .toList();
        assertThat(disallowed)
                .as("커넥션 획득 타임아웃 외의 실패는 허용하지 않는다. 실제 원인: %s",
                        describe(disallowed))
                .isEmpty();
    }

    /**
     * 잔액과 원장이 함께 성립하는지 확인한다. 실패 건수와 무관하게 항상 성립해야 한다.
     */
    private void assertInvariantsHold() {
        Account a = accountRepository.findByAccountNumber(accA).orElseThrow();
        Account b = accountRepository.findByAccountNumber(accB).orElseThrow();

        assertThat(a.getBalance().add(b.getBalance()))
                .as("총 잔액 보존 — 거부된 이체가 돈을 만들거나 없애면 안 된다")
                .isEqualByComparingTo(TOTAL);
        assertThat(a.getBalance()).as("A 잔액 음수 불가").isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(b.getBalance()).as("B 잔액 음수 불가").isGreaterThanOrEqualTo(BigDecimal.ZERO);

        assertThat(reconciliationService.findInconsistentAccounts())
                .as("계좌별 잔액 == 원장 합계").isEmpty();
        assertThat(reconciliationService.findUnbalancedJournals())
                .as("모든 분개의 차변/대변 합이 0").isEmpty();
        assertThat(reconciliationService.trialBalance())
                .as("전 원장 시산표 == 0").isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * 커넥션 획득 타임아웃인지 판별한다.
     *
     * <p>이 실패는 {@code CannotCreateTransactionException} → {@code JDBCConnectionException} 처럼
     * 여러 겹으로 감싸져 올라오므로, 최상위 타입만 보면 놓친다. 원인 체인을 끝까지 훑어야 한다.
     */
    private static boolean isConnectionAcquisitionTimeout(Throwable t) {
        for (Throwable cur = t; cur != null; cur = (cur.getCause() == cur ? null : cur.getCause())) {
            if (cur instanceof SQLTransientConnectionException) {
                return true;
            }
        }
        return false;
    }

    private void logOutcome(List<Throwable> errors) {
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (Throwable t : errors) {
            byType.merge(rootCauseName(t), 1, Integer::sum);
        }
        long completedTx = transactionRepository.findAll().stream()
                .filter(t -> t.getStatus() == Transaction.TransactionStatus.COMPLETED)
                .count();
        Account a = accountRepository.findByAccountNumber(accA).orElseThrow();
        Account b = accountRepository.findByAccountNumber(accB).orElseThrow();

        System.out.printf("[포화] 요청=%d 실패=%d %s 완료거래=%d | A=%s B=%s 합=%s%n",
                PAIRS * 2, errors.size(), byType, completedTx,
                a.getBalance(), b.getBalance(), a.getBalance().add(b.getBalance()));
    }

    private static String describe(List<Throwable> throwables) {
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (Throwable t : throwables) {
            byType.merge(rootCauseName(t), 1, Integer::sum);
        }
        return byType.toString();
    }

    private static String rootCauseName(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName();
    }

    private void runTransfer(String from, String to, BigDecimal amount,
                             List<Throwable> errors, CountDownLatch start, CountDownLatch done) {
        try {
            start.await();
            transferService.transfer(userId, new TransferRequest(
                    UUID.randomUUID().toString(), from, to, amount, "포화"));
        } catch (Throwable t) {
            errors.add(t);
        } finally {
            done.countDown();
        }
    }

    /** 초기 잔액의 원장 leg까지 남기는 정식 개설 경로. 생성된 계좌번호를 돌려준다. */
    private String openAccount() {
        return accountService.openAccount(userId,
                new AccountOpenRequest(UUID.randomUUID().toString(), INITIAL_EACH)).accountNumber();
    }

    private void clearAll() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }
}
