package com.ibank.batch;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.entity.Transaction.TransactionStatus;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정산 배치의 홀드 검증. 정상 흐름에서는 위반이 없고, 불변식이 깨진 상태를 직접 만들어 넣으면
 * 해당 항목만 위반으로 잡히며 잡이 실패하는지 확인한다.
 *
 * <p>불변식은 코드의 락 규율이 지키므로 정상 경로로는 깨진 상태를 만들 수 없다. 그래서 SQL로 직접 고쳐
 * "버그가 있었다면 생겼을 상태"를 만든다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class HoldReconciliationTest {

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

    private static final String[] CHECKS = {
            "UNBALANCED_JOURNALS", "TRIAL_BALANCE", "HOLD_SUM_EXCEEDS_BALANCE",
            "ACTIVE_HOLD_WITHOUT_OPEN_TRANSACTION", "OPEN_TRANSACTION_WITHOUT_ACTIVE_HOLD",
            "UNEXECUTED_TRANSACTION_WITH_LEDGER"};

    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private ScriptedFraudDecisionPort fds;
    @Autowired private JobLauncher jobLauncher;
    @Autowired @Qualifier("reconciliationJob") private Job reconciliationJob;
    @Autowired private TransferService transferService;
    @Autowired private TransactionProcessor processor;
    @Autowired private AccountService accountService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long userId;
    private String accA;
    private String accB;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        jdbcTemplate.update("DELETE FROM reconciliation_check_results");
        fds.reset();
        userId = userRepository.save(User.builder()
                .loginId("recon-hold").password("x").name("정산").email("recon-hold@test.com")
                .birthDate(LocalDate.of(1950, 1, 1)).role(User.UserRole.ROLE_USER).build()).getId();
        accA = open(new BigDecimal("1000000"));
        accB = open(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("보류·실행·취소가 섞인 정상 상태에서는 모든 검증이 위반 0건으로 기록되고 잡이 완료된다")
    void normalFlow_allChecksPass() throws Exception {
        transferService.transfer(userId, transfer("10000"));            // 완료
        Long executed = hold("20000");
        Long cancelled = hold("30000");
        hold("40000");                                                  // 보류 중으로 남긴다
        processor.executeHeld(executed);
        processor.cancelHeld(cancelled);

        JobExecution execution = run();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Long> violations = violationsOf(execution);
        assertThat(violations).containsOnlyKeys(CHECKS); // 위반이 없어도 모든 항목이 기록된다
        assertThat(violations.values()).containsOnly(0L);
    }

    @Test
    @DisplayName("ACTIVE 홀드 합계가 원장잔액을 넘으면 위반으로 잡는다")
    void holdSumExceedsBalance() throws Exception {
        Long txId = hold("40000");
        jdbcTemplate.update("UPDATE account_holds SET amount = 5000000 WHERE transaction_id = ?", txId);

        assertOnlyViolated(run(), "HOLD_SUM_EXCEEDS_BALANCE");
    }

    @Test
    @DisplayName("이미 끝난 거래에 ACTIVE 홀드가 남아 있으면 위반으로 잡는다")
    void activeHoldOnClosedTransaction() throws Exception {
        Long txId = hold("40000");
        // 거래만 취소로 바뀌고 홀드 해제가 빠진 상태
        jdbcTemplate.update("UPDATE transactions SET status = 'CANCELLED' WHERE id = ?", txId);

        assertOnlyViolated(run(), "ACTIVE_HOLD_WITHOUT_OPEN_TRANSACTION");
    }

    @Test
    @DisplayName("보류 중인 거래에 ACTIVE 홀드가 없으면 위반으로 잡는다")
    void openTransactionWithoutHold() throws Exception {
        Long txId = hold("40000");
        // 홀드만 풀리고 거래는 보류로 남은 상태 = 금액이 묶이지 않은 보류
        jdbcTemplate.update("UPDATE account_holds SET status = 'RELEASED' WHERE transaction_id = ?", txId);

        assertOnlyViolated(run(), "OPEN_TRANSACTION_WITHOUT_ACTIVE_HOLD");
    }

    @Test
    @DisplayName("취소된 거래에 분개가 달려 있으면 위반으로 잡는다")
    void cancelledTransactionWithLedger() throws Exception {
        Long txId = hold("40000");
        processor.cancelHeld(txId);
        // 같은 계좌에 DEBIT과 CREDIT을 한 분개로 넣는다: 분개 합도 0, 계좌 원장 합계도 그대로라
        // 다른 검증에는 걸리지 않고 "취소 거래에 분개가 있다"만 남는다
        Long accountId = jdbcTemplate.queryForObject("SELECT id FROM accounts WHERE account_number = ?", Long.class, accA);
        for (String direction : new String[]{"DEBIT", "CREDIT"}) {
            jdbcTemplate.update("""
                    INSERT INTO ledger_entries (journal_id, transaction_id, account_id, direction, amount, balance_after, created_at)
                    VALUES ('stray-journal', ?, ?, ?, 40000, 1000000, now())
                    """, txId, accountId, direction);
        }

        assertOnlyViolated(run(), "UNEXECUTED_TRANSACTION_WITH_LEDGER");
    }

    // ---- 헬퍼 ----

    /** 지정한 항목 하나만 위반(1건)이고 나머지는 0건이며, 잡은 실패로 끝나는지 확인한다. */
    private void assertOnlyViolated(JobExecution execution, String expectedCheck) {
        assertThat(execution.getStatus()).as("위반이 있으면 잡이 실패로 끝나 시끄럽게 드러난다").isEqualTo(BatchStatus.FAILED);
        Map<String, Long> violations = violationsOf(execution);
        assertThat(violations).as("잡이 실패해도 무엇이 위반이었는지는 남는다").containsOnlyKeys(CHECKS);
        violations.forEach((check, count) ->
                assertThat(count).as(check).isEqualTo(check.equals(expectedCheck) ? 1L : 0L));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sample FROM reconciliation_check_results WHERE job_execution_id = ? AND check_name = ?",
                String.class, execution.getId(), expectedCheck)).isNotBlank();
    }

    private Map<String, Long> violationsOf(JobExecution execution) {
        return jdbcTemplate.queryForList(
                        "SELECT check_name, violations FROM reconciliation_check_results WHERE job_execution_id = ?",
                        execution.getId()).stream()
                .collect(Collectors.toMap(row -> (String) row.get("check_name"),
                        row -> ((Number) row.get("violations")).longValue()));
    }

    private JobExecution run() throws Exception {
        return jobLauncher.run(reconciliationJob,
                new JobParametersBuilder().addLong("runAt", System.nanoTime()).toJobParameters());
    }

    private Long hold(String amount) {
        fds.holdAll("NEW_PAYEE_HIGH_VALUE");
        TransferResponse held = transferService.transfer(userId, transfer(amount));
        assertThat(held.status()).isEqualTo(TransactionStatus.HELD);
        fds.approveAll();
        return held.transactionId();
    }

    private String open(BigDecimal balance) {
        return accountService.openAccount(userId,
                new AccountOpenRequest(UUID.randomUUID().toString(), balance)).accountNumber();
    }

    private TransferRequest transfer(String amount) {
        return new TransferRequest(UUID.randomUUID().toString(), accA, accB, new BigDecimal(amount), "정산 검증");
    }
}
