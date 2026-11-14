package com.ibank.bulkload;

import com.ibank.domain.ledger.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 합성 데이터 적재의 실행: 적재 잡 → 정산 배치 → 결과 요약.
 *
 * <p>적재가 끝나면 곧바로 정산 배치를 돌린다. 적재는 평소의 거래 경로를 거치지 않고 원장을 직접 쓰므로,
 * "모든 계좌의 잔액이 원장 합계와 같고 모든 분개의 합이 0"임을 적재 직후에 확인해야 그 데이터 위에서
 * 다른 것을 시작할 수 있다.
 */
@Slf4j
@Service
@Profile("bulk-load")
@RequiredArgsConstructor
public class BulkLoadService {

    private final JobLauncher jobLauncher;
    private final Job bulkLoadJob;
    private final Job reconciliationJob;
    private final BulkLoadStats stats;
    private final ReconciliationService reconciliationService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 적재 결과 요약.
     *
     * @param steps                스텝별(customers, accounts, transactions) 건수
     * @param loadStatus           적재 잡 상태
     * @param reconciliationStatus 정산 배치 상태. 복식부기 위반이 있으면 FAILED
     * @param inconsistentAccounts 잔액과 원장 합계가 다른 계좌 수
     * @param unbalancedJournals   합이 0이 아닌 분개 수
     * @param trialBalance         전 원장 시산표(0이어야 한다)
     */
    public record Summary(Map<String, BulkLoadStats.StepCounts> steps, BatchStatus loadStatus,
                          BatchStatus reconciliationStatus, long inconsistentAccounts, long unbalancedJournals,
                          BigDecimal trialBalance, Long loadJobExecutionId) {

        /** 적재와 정산이 모두 끝까지 돌았고 불일치가 없는지. 건너뛴 행이 있어도 정합성과는 별개다. */
        public boolean consistent() {
            return loadStatus == BatchStatus.COMPLETED && reconciliationStatus == BatchStatus.COMPLETED
                    && inconsistentAccounts == 0 && unbalancedJournals == 0 && trialBalance.signum() == 0;
        }

        public long totalErrors() {
            return steps.values().stream().mapToLong(BulkLoadStats.StepCounts::errors).sum();
        }
    }

    public Summary load(Path inputDir) throws Exception {
        if (!Files.isDirectory(inputDir)) {
            throw new IllegalArgumentException("입력 디렉터리가 없습니다: " + inputDir);
        }
        stats.reset();

        // inputDir만 식별 파라미터로 둔다. 같은 디렉터리로 다시 실행하면 Spring Batch가 같은 잡 인스턴스로 보고,
        // DB에 저장된 메타데이터(BATCH_* 테이블)를 읽어 실패한 스텝의 마지막 커밋 지점부터 재시작한다.
        // 이미 성공한 인스턴스는 다시 돌지 않는다 — 새 데이터는 새 디렉터리에 둔다.
        JobExecution load;
        try {
            load = jobLauncher.run(bulkLoadJob, new JobParametersBuilder()
                    .addString("inputDir", inputDir.toAbsolutePath().normalize().toString())
                    .toJobParameters());
        } catch (JobInstanceAlreadyCompleteException e) {
            throw new AlreadyLoadedException(inputDir);
        }

        JobExecution reconciliation = jobLauncher.run(reconciliationJob, new JobParametersBuilder()
                .addLong("runAt", System.currentTimeMillis())
                .toJobParameters());

        Map<String, BulkLoadStats.StepCounts> steps = new LinkedHashMap<>();
        for (String step : List.of("customers", "accounts", "transactions")) {
            steps.put(step, stats.of(step));
        }
        Long inconsistent = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_results WHERE job_execution_id = ? AND consistent = false",
                Long.class, reconciliation.getId());
        Summary summary = new Summary(steps, load.getStatus(), reconciliation.getStatus(),
                inconsistent == null ? 0 : inconsistent,
                reconciliationService.findUnbalancedJournals().size(),
                reconciliationService.trialBalance(),
                load.getId());
        print(summary);
        return summary;
    }

    /**
     * 이 디렉터리는 이미 끝까지 적재됐다. Spring Batch는 같은 식별 파라미터로 성공한 잡을 다시 돌리지 않는다.
     * 데이터를 고쳤거나 추가했다면 새 디렉터리에 두고 적재한다(이미 적재된 행은 키로 알아보고 건너뛴다).
     */
    public static class AlreadyLoadedException extends RuntimeException {
        public AlreadyLoadedException(Path inputDir) {
            super("이미 끝까지 적재된 디렉터리입니다: " + inputDir
                    + " — 고치거나 추가한 데이터는 새 디렉터리에 두고 적재하세요.");
        }
    }

    private void print(Summary summary) {
        StringBuilder out = new StringBuilder("\n========== 합성 데이터 적재 결과 ==========\n");
        summary.steps().forEach((step, counts) -> out.append(String.format(
                "  %-13s 적재 %,d건 / 이미 있음 %,d건 / 건너뜀 %,d건%n",
                step, counts.loaded(), counts.alreadyLoaded(), counts.errors())));
        out.append(String.format("  적재 잡: %s (jobExecutionId=%d)%n", summary.loadStatus(), summary.loadJobExecutionId()));
        out.append(String.format("  정산 배치: %s — 불일치 계좌 %d, 불균형 분개 %d, 시산표 %s%n",
                summary.reconciliationStatus(), summary.inconsistentAccounts(), summary.unbalancedJournals(),
                summary.trialBalance().toPlainString()));
        if (summary.totalErrors() > 0) {
            out.append(String.format("  건너뛴 행 %,d건의 이유: SELECT step, line_number, reason FROM bulk_load_errors "
                    + "WHERE job_execution_id = %d ORDER BY step, line_number;%n",
                    summary.totalErrors(), summary.loadJobExecutionId()));
        }
        out.append(summary.consistent() ? "  결과: 정합성 이상 없음\n" : "  결과: 확인 필요 (위 항목을 보세요)\n");
        out.append("============================================");
        log.info(out.toString());
    }
}
