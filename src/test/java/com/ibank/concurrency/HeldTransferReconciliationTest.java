package com.ibank.concurrency;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.decision.FraudDecisionPort.Decision;
import com.ibank.domain.decision.FraudDecisionPort.RiskLevel;
import com.ibank.domain.decision.FraudDecisionPort.Source;
import com.ibank.domain.decision.FraudDecisionPort.Verdict;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.entity.Transaction.TransactionStatus;
import com.ibank.domain.transaction.exception.TransactionNotHeldException;
import com.ibank.domain.transaction.service.TransactionProcessor;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.support.DatabaseCleaner;
import com.ibank.support.ScriptedFraudDecisionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 완료 기준의 비기능 항목: 홀드가 섞인 이체 1,000건을 동시에 처리한 뒤 정산 배치를 돌리면 불일치가 0건이어야 한다.
 *
 * <p>계좌 10개 사이에서 이체 1,000건을 16개 스레드로 동시에 보낸다. 판정은 거래 ID로 정해 약 3분의 1을 보류한다.
 * 이어서 보류 거래의 실행·취소를 동시에 처리하고, 일부는 실행과 취소를 같은 순간에 보내 경합시키며,
 * 나머지는 보류로 남긴다. 그 상태에서 정산 배치를 돌려
 * <ul>
 *   <li>계좌별 잔액 = 원장 합계 (reconciliation_results 전부 일치)</li>
 *   <li>분개·시산표·홀드 불변식 6항목 위반 0건</li>
 * </ul>
 * 을 확인하고, 고객 계좌 잔액 총합이 처음과 같은지, 묶인 금액이 남은 보류 거래 금액과 같은지도 본다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class HeldTransferReconciliationTest {

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

    private static final int ACCOUNTS = 10;
    private static final int TRANSFERS = 1_000;
    private static final int THREADS = 16;
    private static final BigDecimal INITIAL = new BigDecimal("5000000");

    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private ScriptedFraudDecisionPort fds;
    @Autowired private JobLauncher jobLauncher;
    @Autowired @Qualifier("reconciliationJob") private Job reconciliationJob;
    @Autowired private TransferService transferService;
    @Autowired private TransactionProcessor processor;
    @Autowired private AccountService accountService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> userIds = new ArrayList<>();
    private final List<String> accounts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        jdbcTemplate.update("DELETE FROM reconciliation_check_results");
        jdbcTemplate.update("DELETE FROM reconciliation_results");
        fds.reset();
        userIds.clear();
        accounts.clear();
        for (int i = 0; i < ACCOUNTS; i++) {
            Long userId = userRepository.save(User.builder()
                    .loginId("bulk-" + i).password("x").name("고객" + i).email("bulk-" + i + "@test.com")
                    .birthDate(LocalDate.of(1955, 1, 1)).role(User.UserRole.ROLE_USER).build()).getId();
            userIds.add(userId);
            accounts.add(accountService.openAccount(userId,
                    new AccountOpenRequest(UUID.randomUUID().toString(), INITIAL)).accountNumber());
        }
        // 거래 ID로 판정을 정한다: 3의 배수만 보류. 실행할 때마다 같은 비율이 나온다
        fds.decideBy(request -> request.transactionId() % 3 == 0
                ? new Decision(Verdict.HOLD, "d-" + request.transactionId(), RiskLevel.DANGER,
                        List.of("NEW_PAYEE_HIGH_VALUE"), Source.FDS)
                : new Decision(Verdict.APPROVE, "d-" + request.transactionId(), RiskLevel.NORMAL,
                        List.of(), Source.FDS));
    }

    @Test
    @DisplayName("홀드가 섞인 동시 이체 1,000건과 동시 실행·취소 뒤 정산 배치의 불일치가 0건이다")
    void thousandConcurrentTransfersWithHolds_reconcileCleanly() throws Exception {
        BigDecimal totalBefore = customerBalanceSum();

        // 1) 이체 1,000건을 동시에
        Queue<TransferResponse> responses = new ConcurrentLinkedQueue<>();
        Queue<Throwable> transferErrors = new ConcurrentLinkedQueue<>();
        List<Callable<Void>> transfers = new ArrayList<>();
        for (int k = 0; k < TRANSFERS; k++) {
            int from = k % ACCOUNTS;
            int to = (from + 1 + (k / ACCOUNTS) % (ACCOUNTS - 1)) % ACCOUNTS;
            BigDecimal amount = BigDecimal.valueOf(1_000L + (k * 37L % 50) * 1_000L);
            transfers.add(() -> {
                try {
                    responses.add(transferService.transfer(userIds.get(from), new TransferRequest(
                            UUID.randomUUID().toString(), accounts.get(from), accounts.get(to), amount, "동시 이체")));
                } catch (Throwable e) {
                    transferErrors.add(e);
                }
                return null;
            });
        }
        runConcurrently(transfers);

        assertThat(transferErrors).as("한도·잔액 안의 이체라 실패가 없어야 한다").isEmpty();
        assertThat(responses).hasSize(TRANSFERS);
        List<Long> held = responses.stream()
                .filter(r -> r.status() == TransactionStatus.HELD).map(TransferResponse::transactionId).toList();
        long completed = responses.stream().filter(r -> r.status() == TransactionStatus.COMPLETED).count();
        assertThat(held.size() + completed).isEqualTo(TRANSFERS);
        assertThat(held).as("보류가 충분히 섞여 있어야 의미가 있다").hasSizeGreaterThan(TRANSFERS / 5);

        // 2) 보류 거래를 동시에 정리: 4개 중 하나는 실행·취소 경합, 하나는 실행, 하나는 취소, 하나는 보류로 남김
        AtomicInteger raceWins = new AtomicInteger();
        AtomicInteger raceLosses = new AtomicInteger();
        Queue<Throwable> resolveErrors = new ConcurrentLinkedQueue<>();
        List<Long> stillHeld = new ArrayList<>();
        List<Callable<Void>> resolutions = new ArrayList<>();
        int raced = 0;
        for (int i = 0; i < held.size(); i++) {
            Long txId = held.get(i);
            switch (i % 4) {
                case 0 -> {
                    raced++;
                    resolutions.add(race(() -> processor.executeHeld(txId), raceWins, raceLosses, resolveErrors));
                    resolutions.add(race(() -> processor.cancelHeld(txId), raceWins, raceLosses, resolveErrors));
                }
                case 1 -> resolutions.add(plain(() -> processor.executeHeld(txId), resolveErrors));
                case 2 -> resolutions.add(plain(() -> processor.cancelHeld(txId), resolveErrors));
                default -> stillHeld.add(txId);
            }
        }
        runConcurrently(resolutions);

        assertThat(resolveErrors).as("경합에서 진 쪽의 409 외에는 실패가 없어야 한다").isEmpty();
        assertThat(raceWins.get()).as("경합한 거래마다 정확히 한쪽만 이긴다").isEqualTo(raced);
        assertThat(raceLosses.get()).isEqualTo(raced);

        // 3) 정산 배치
        JobExecution execution = jobLauncher.run(reconciliationJob,
                new JobParametersBuilder().addLong("runAt", System.nanoTime()).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Long mismatches = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation_results WHERE job_execution_id = ? AND NOT consistent",
                Long.class, execution.getId());
        Long checked = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reconciliation_results WHERE job_execution_id = ?",
                Long.class, execution.getId());
        assertThat(checked).as("고객 계좌가 모두 대사됐다").isGreaterThanOrEqualTo(ACCOUNTS);
        assertThat(mismatches).as("잔액과 원장 합계의 불일치").isZero();

        List<Map<String, Object>> checks = jdbcTemplate.queryForList(
                "SELECT check_name, violations FROM reconciliation_check_results WHERE job_execution_id = ?",
                execution.getId());
        assertThat(checks).hasSize(6);
        checks.forEach(row -> assertThat(((Number) row.get("violations")).longValue())
                .as((String) row.get("check_name")).isZero());

        // 4) 돈의 총량과 묶인 금액
        assertThat(customerBalanceSum()).as("고객 계좌 사이의 이체라 총액은 그대로").isEqualByComparingTo(totalBefore);
        BigDecimal activeHolds = jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(amount), 0) FROM account_holds WHERE status = 'ACTIVE'", BigDecimal.class);
        BigDecimal stillHeldAmount = stillHeld.isEmpty() ? BigDecimal.ZERO : jdbcTemplate.queryForObject(
                "SELECT coalesce(sum(amount), 0) FROM transactions WHERE status = 'HELD' AND id IN ("
                        + String.join(",", stillHeld.stream().map(String::valueOf).toList()) + ")", BigDecimal.class);
        assertThat(activeHolds).as("묶인 금액 = 남은 보류 거래 금액").isEqualByComparingTo(stillHeldAmount);
        Long heldNow = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transactions WHERE status = 'HELD'", Long.class);
        assertThat(heldNow).isEqualTo(stillHeld.size());
        Long negativeAvailable = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM accounts a
                WHERE a.balance - coalesce((SELECT sum(h.amount) FROM account_holds h
                                            WHERE h.account_id = a.id AND h.status = 'ACTIVE'), 0) < 0
                """, Long.class);
        assertThat(negativeAvailable).as("가용잔액이 음수인 계좌").isZero();
    }

    // ---- 헬퍼 ----

    private Callable<Void> race(Runnable action, AtomicInteger wins, AtomicInteger losses, Queue<Throwable> errors) {
        return () -> {
            try {
                action.run();
                wins.incrementAndGet();
            } catch (TransactionNotHeldException e) {
                losses.incrementAndGet();
            } catch (Throwable e) {
                errors.add(e);
            }
            return null;
        };
    }

    private Callable<Void> plain(Runnable action, Queue<Throwable> errors) {
        return () -> {
            try {
                action.run();
            } catch (Throwable e) {
                errors.add(e);
            }
            return null;
        };
    }

    /** 모든 작업을 한꺼번에 출발시키고 끝날 때까지 기다린다. */
    private void runConcurrently(List<Callable<Void>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<Void> f : futures) {
                f.get(5, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private BigDecimal customerBalanceSum() {
        return jdbcTemplate.queryForObject(
                "SELECT sum(balance) FROM accounts WHERE account_number IN ("
                        + String.join(",", accounts.stream().map(a -> "'" + a + "'").toList()) + ")",
                BigDecimal.class);
    }
}
