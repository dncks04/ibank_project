package com.ibank.bulkload;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.function.Function;

/**
 * 합성 데이터 대량 적재 잡. {@code bulk-load} 프로파일에서만 등록된다.
 *
 * <p>세 스텝이 순서대로 돈다: 고객 → 계좌 → 거래. 각 스텝은 JSON Lines 파일을 한 줄씩 읽어
 * 청크 단위({@code ibank.bulk-load.chunk-size}, 기본 500행)로 커밋한다.
 *
 * <ul>
 *   <li><b>청크 커밋</b>: 한 청크가 한 트랜잭션이다. 쓰기 트랜잭션이 짧게 끊기므로 적재 중에도 Outbox 공개가
 *       오래 막히지 않는다.</li>
 *   <li><b>실패 행 기록</b>: 한 행의 문제는 그 행만 건너뛰고 {@code bulk_load_errors}에 남긴다.</li>
 *   <li><b>재시작</b>: 리더가 읽은 줄 수를 Spring Batch 메타데이터에 저장하므로, 같은 입력 디렉터리로 다시 실행하면
 *       실패한 스텝의 마지막 커밋 지점부터 이어 간다. 이어 가지 않고 처음부터 다시 읽더라도 이미 적재된 행은
 *       키(loginId, accountNumber, txRef)로 알아보고 건너뛰므로 두 번 적재되지 않는다.</li>
 * </ul>
 */
@Configuration
@Profile("bulk-load")
@EnableConfigurationProperties(BulkLoadProperties.class)
@RequiredArgsConstructor
public class BulkLoadBatchConfig {

    static final String CUSTOMERS_FILE = "customers.jsonl";
    static final String ACCOUNTS_FILE = "accounts.jsonl";
    static final String TRANSACTIONS_FILE = "transactions.jsonl";

    private final BulkLoadProperties properties;
    private final BulkLoadApplier applier;
    private final BulkLoadStats stats;
    private final JdbcTemplate jdbcTemplate;

    /** 입력의 한 줄. 줄 번호는 실패 행 기록에 쓴다. */
    public record Line(int number, String text) {}

    // ---- 리더: 파일마다 하나. 줄 번호를 붙여 그대로 넘긴다(해석은 적용 단계에서 한다) ----

    @Bean
    @StepScope
    public FlatFileItemReader<Line> customerLineReader(@Value("#{jobParameters['inputDir']}") String inputDir) {
        return lineReader("bulkLoadCustomerReader", inputDir, CUSTOMERS_FILE);
    }

    @Bean
    @StepScope
    public FlatFileItemReader<Line> accountLineReader(@Value("#{jobParameters['inputDir']}") String inputDir) {
        return lineReader("bulkLoadAccountReader", inputDir, ACCOUNTS_FILE);
    }

    @Bean
    @StepScope
    public FlatFileItemReader<Line> transactionLineReader(@Value("#{jobParameters['inputDir']}") String inputDir) {
        return lineReader("bulkLoadTransactionReader", inputDir, TRANSACTIONS_FILE);
    }

    private FlatFileItemReader<Line> lineReader(String name, String inputDir, String fileName) {
        return new FlatFileItemReaderBuilder<Line>()
                .name(name) // 재시작 지점을 메타데이터에 저장할 때의 키
                .resource(new FileSystemResource(Path.of(inputDir, fileName)))
                .encoding("UTF-8")
                .lineMapper((text, lineNumber) -> new Line(lineNumber, text))
                .strict(true) // 파일이 없으면 스텝을 실패시킨다(빈 파일은 허용)
                .build();
    }

    // ---- 라이터: 행을 하나씩 적용하고, 건너뛴 행은 이유와 함께 기록한다 ----

    @Bean
    @StepScope
    public ItemWriter<Line> customerRowWriter(@Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        return rowWriter("customers", jobExecutionId, applier::applyCustomer);
    }

    @Bean
    @StepScope
    public ItemWriter<Line> accountRowWriter(@Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        return rowWriter("accounts", jobExecutionId, applier::applyAccount);
    }

    @Bean
    @StepScope
    public ItemWriter<Line> transactionRowWriter(@Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        return rowWriter("transactions", jobExecutionId, applier::applyTransaction);
    }

    private ItemWriter<Line> rowWriter(String step, Long jobExecutionId, Function<String, BulkLoadApplier.Result> apply) {
        return chunk -> {
            for (Line line : chunk) {
                if (line.text().isBlank()) {
                    continue; // 빈 줄은 조용히 넘긴다
                }
                try {
                    stats.count(step, apply.apply(line.text()));
                } catch (BulkLoadRowException e) {
                    // 검증에서 걸린 행: DB는 아직 바뀌지 않았다. 이유를 남기고 다음 행으로 간다.
                    recordError(jobExecutionId, step, line, e.getMessage());
                    stats.countError(step);
                }
            }
        };
    }

    private void recordError(Long jobExecutionId, String step, Line line, String reason) {
        jdbcTemplate.update("""
                INSERT INTO bulk_load_errors (job_execution_id, step, line_number, raw_line, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, jobExecutionId, step, line.number(), truncate(line.text(), 2000), truncate(reason, 500),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ---- 스텝과 잡 ----

    @Bean
    public Step bulkLoadCustomerStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                     FlatFileItemReader<Line> customerLineReader, ItemWriter<Line> customerRowWriter) {
        return step("bulkLoadCustomerStep", jobRepository, transactionManager, customerLineReader, customerRowWriter);
    }

    @Bean
    public Step bulkLoadAccountStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                    FlatFileItemReader<Line> accountLineReader, ItemWriter<Line> accountRowWriter) {
        return step("bulkLoadAccountStep", jobRepository, transactionManager, accountLineReader, accountRowWriter);
    }

    @Bean
    public Step bulkLoadTransactionStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                                        FlatFileItemReader<Line> transactionLineReader,
                                        ItemWriter<Line> transactionRowWriter) {
        return step("bulkLoadTransactionStep", jobRepository, transactionManager,
                transactionLineReader, transactionRowWriter);
    }

    private Step step(String name, JobRepository jobRepository, PlatformTransactionManager transactionManager,
                      FlatFileItemReader<Line> reader, ItemWriter<Line> writer) {
        return new StepBuilder(name, jobRepository)
                .<Line, Line>chunk(properties.chunkSize(), transactionManager)
                .reader(reader)
                .writer(writer)
                .build();
    }

    @Bean
    public Job bulkLoadJob(JobRepository jobRepository, Step bulkLoadCustomerStep, Step bulkLoadAccountStep,
                           Step bulkLoadTransactionStep) {
        return new JobBuilder("bulkLoadJob", jobRepository)
                .start(bulkLoadCustomerStep)
                .next(bulkLoadAccountStep)
                .next(bulkLoadTransactionStep)
                .build();
    }
}
