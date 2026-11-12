package com.ibank.outbox;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.support.DatabaseCleaner;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치가 쓰기 트랜잭션을 오래 열어 두지 않는지 측정한다.
 *
 * <p>Outbox 이벤트는 "가장 오래된 쓰기 트랜잭션"보다 앞선 것만 공개된다. 그래서 배치 하나가 계좌 전체를
 * 한 트랜잭션으로 처리하면, 배치가 도는 내내 이벤트 전달이 멈춘다. 이자 적립·지급과 정산 배치가 계좌 수가
 * 늘어도 짧은 트랜잭션 여러 개로 나뉘어 도는지, 배치가 도는 동안 DB에 열려 있던 쓰기 트랜잭션의 최대 나이를
 * {@code pg_stat_activity}로 재서 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class BatchWriteTransactionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 청크 크기(100)의 몇 배가 되도록 잡아, 한 트랜잭션으로 처리하면 눈에 띄게 길어지게 한다. */
    private static final int ACCOUNTS = 450;

    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private JobLauncher jobLauncher;
    @Autowired @Qualifier("interestAccrualJob") private Job interestAccrualJob;
    @Autowired @Qualifier("interestPaymentJob") private Job interestPaymentJob;
    @Autowired @Qualifier("reconciliationJob") private Job reconciliationJob;
    @Autowired private AccountService accountService;
    @Autowired private UserRepository userRepository;
    @Autowired private DataSource dataSource;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        Long userId = userRepository.save(User.builder()
                .loginId("batch-tx-user").password("x").name("배치").email("batch-tx@test.com")
                .birthDate(LocalDate.of(1990, 1, 1)).role(User.UserRole.ROLE_USER).build()).getId();
        for (int i = 0; i < ACCOUNTS; i++) {
            accountService.openAccount(userId,
                    new AccountOpenRequest(UUID.randomUUID().toString(), new BigDecimal("3650000")));
        }
    }

    @Test
    @DisplayName("이자 적립·지급, 정산 배치는 계좌가 많아도 쓰기 트랜잭션을 오래 열어 두지 않는다")
    void batches_doNotHoldLongWriteTransactions() throws Exception {
        Measured accrual = measure(() -> run(interestAccrualJob, "accrualDate", "2026-06-01"));
        Measured payment = measure(() -> run(interestPaymentJob, null, null));
        Measured reconciliation = measure(() -> run(reconciliationJob, null, null));

        System.out.printf("[배치 쓰기 트랜잭션] 계좌 %d개 | 적립: 전체 %dms, 최장 쓰기 트랜잭션 %dms | "
                        + "지급: 전체 %dms, 최장 %dms | 정산: 전체 %dms, 최장 %dms%n",
                ACCOUNTS, accrual.total.toMillis(), accrual.longestWriteTx.toMillis(),
                payment.total.toMillis(), payment.longestWriteTx.toMillis(),
                reconciliation.total.toMillis(), reconciliation.longestWriteTx.toMillis());

        // 쓰기 트랜잭션 하나의 나이가 배치 전체 시간에 가까우면 "한 트랜잭션으로 전부 처리"한 것이다.
        // 청크(100건)·계좌 단위로 나뉘어 있으면 전체의 일부에 그친다(계좌 450개면 청크 5개 → 대략 1/5).
        // 실행 환경의 속도와 무관하게 판정하도록 절대 시간이 아니라 전체 대비 비율로 본다.
        assertSplitIntoShortTransactions("이자 적립", accrual);
        assertSplitIntoShortTransactions("이자 지급", payment);
        assertSplitIntoShortTransactions("정산", reconciliation);
    }

    private static void assertSplitIntoShortTransactions(String name, Measured measured) {
        assertThat(measured.longestWriteTx.toMillis())
                .as("%s 배치: 최장 쓰기 트랜잭션(%dms)이 전체 실행 시간(%dms)의 절반을 넘으면 안 된다",
                        name, measured.longestWriteTx.toMillis(), measured.total.toMillis())
                .isLessThan(measured.total.toMillis() / 2);
    }

    private void run(Job job, String paramName, String paramValue) {
        try {
            JobParametersBuilder params = new JobParametersBuilder().addLong("runAt", System.nanoTime());
            if (paramName != null) {
                params.addString(paramName, paramValue);
            }
            JobExecution execution = jobLauncher.run(job, params.toJobParameters());
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Measured(Duration total, Duration longestWriteTx) {}

    /**
     * 작업이 도는 동안 별도 커넥션으로 pg_stat_activity를 계속 읽어, 트랜잭션 ID를 받은(= 쓰기를 한)
     * 트랜잭션 가운데 가장 오래 열려 있던 것의 나이를 기록한다.
     */
    private Measured measure(Runnable work) throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong longestMillis = new AtomicLong();
        Thread sampler = new Thread(() -> {
            try (Connection connection = dataSource.getConnection(); Statement st = connection.createStatement()) {
                while (running.get()) {
                    try (ResultSet rs = st.executeQuery("""
                            SELECT COALESCE(MAX(EXTRACT(EPOCH FROM (clock_timestamp() - xact_start)) * 1000), 0)
                            FROM pg_stat_activity
                            WHERE backend_xid IS NOT NULL
                              AND datname = current_database()
                              AND pid <> pg_backend_pid()
                            """)) {
                        rs.next();
                        longestMillis.accumulateAndGet((long) rs.getDouble(1), Math::max);
                    }
                    Thread.sleep(5);
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        sampler.start();
        long started = System.nanoTime();
        try {
            work.run();
        } finally {
            running.set(false);
            sampler.join(5_000);
        }
        return new Measured(Duration.ofNanos(System.nanoTime() - started), Duration.ofMillis(longestMillis.get()));
    }
}
