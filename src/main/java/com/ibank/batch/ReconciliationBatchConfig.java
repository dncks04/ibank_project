package com.ibank.batch;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.ledger.dto.AccountLedgerBalance;
import com.ibank.domain.ledger.entity.ReconciliationResult;
import com.ibank.domain.ledger.repository.ReconciliationResultRepository;
import com.ibank.domain.ledger.service.IntegrityCheckService;
import com.ibank.domain.ledger.service.ReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.data.RepositoryItemReader;
import org.springframework.batch.infrastructure.item.data.RepositoryItemWriter;
import org.springframework.batch.infrastructure.item.data.builder.RepositoryItemReaderBuilder;
import org.springframework.batch.infrastructure.item.data.builder.RepositoryItemWriterBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

/**
 * 일일 정산(reconciliation) 배치. 두 스텝으로 구성된다.
 *
 * 1. 계좌별 스텝(청크 지향): 계좌(reader) → 잔액 vs 원장 합계 비교(processor) → 결과 저장(writer).
 *    불변식 {@code balance == SUM(CREDIT) - SUM(DEBIT)} 위반 계좌를 찾아 reconciliation_results에 남긴다.
 * 2. 시스템 전체 스텝(tasklet): 계좌 단위가 아닌 검증({@link IntegrityCheckService}) —
 *    복식부기(모든 분개의 합 0, 전 원장 시산표 0)와 홀드 불변식(홀드 합계 ≤ 잔액, 홀드와 거래 상태의 짝,
 *    실행되지 않은 거래에 분개 없음). 항목별 결과를 reconciliation_check_results에 남기고,
 *    하나라도 위반이면 잡을 FAILED로 종료해 시끄럽게 표면화한다.
 */
@Configuration
@RequiredArgsConstructor
public class ReconciliationBatchConfig {

    private static final int CHUNK_SIZE = 100;

    private final AccountRepository accountRepository;
    private final ReconciliationResultRepository reconciliationResultRepository;
    private final ReconciliationService reconciliationService;
    private final IntegrityCheckService integrityCheckService;

    @Bean
    public RepositoryItemReader<Account> reconciliationReader() {
        return new RepositoryItemReaderBuilder<Account>()
                .name("reconciliationReader")
                .repository(accountRepository)
                .methodName("findAll")
                .pageSize(CHUNK_SIZE)
                .sorts(Map.of("id", Sort.Direction.ASC))
                .build();
    }

    @Bean
    @StepScope
    public ItemProcessor<Account, ReconciliationResult> reconciliationProcessor(
            @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
        // reader가 읽은 account.balance는 사용하지 않는다(읽은 시점이 다름 → read skew 위험).
        // balance와 ledgerSum을 단일 쿼리(단일 스냅샷)로 다시 함께 읽어 정합성 검증의 오탐을 막는다.
        return account -> {
            AccountLedgerBalance row = accountRepository.findAccountLedgerBalance(account.getId())
                    .orElseThrow();
            return ReconciliationResult.of(jobExecutionId, row.getAccountId(), row.getBalance(), row.getLedgerSum());
        };
    }

    @Bean
    public RepositoryItemWriter<ReconciliationResult> reconciliationWriter() {
        return new RepositoryItemWriterBuilder<ReconciliationResult>()
                .repository(reconciliationResultRepository)
                .methodName("save")
                .build();
    }

    @Bean
    public Step reconciliationStep(JobRepository jobRepository,
                                   PlatformTransactionManager transactionManager,
                                   RepositoryItemReader<Account> reconciliationReader,
                                   ItemProcessor<Account, ReconciliationResult> reconciliationProcessor,
                                   RepositoryItemWriter<ReconciliationResult> reconciliationWriter) {
        return new StepBuilder("reconciliationStep", jobRepository)
                .<Account, ReconciliationResult>chunk(CHUNK_SIZE, transactionManager)
                .reader(reconciliationReader)
                .processor(reconciliationProcessor)
                .writer(reconciliationWriter)
                .build();
    }

    /**
     * 시스템 전체 검증 스텝. 계좌별 검사로는 잡지 못하는 자금 보존 위반(잔액과 원장이 함께 잘못된 경우)을
     * 분개 zero-sum + 시산표로, 홀드와 거래 상태가 어긋난 경우를 홀드 검증으로 탐지한다.
     */
    @Bean
    public Step ledgerIntegrityStep(JobRepository jobRepository,
                                    PlatformTransactionManager transactionManager) {
        return new StepBuilder("ledgerIntegrityStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    Long jobExecutionId = chunkContext.getStepContext().getStepExecution().getJobExecutionId();
                    List<IntegrityCheckService.CheckResult> results = integrityCheckService.runAll();
                    // 결과는 별도 트랜잭션으로 저장한다 — 아래에서 예외를 던져 이 스텝이 롤백되어도 남는다
                    integrityCheckService.record(jobExecutionId, results);

                    List<String> violated = results.stream()
                            .filter(result -> !result.consistent())
                            .map(result -> result.checkName() + "(" + result.violations() + "건)")
                            .toList();
                    if (!violated.isEmpty()) {
                        throw new IllegalStateException("정산 검증 위반: " + String.join(", ", violated));
                    }
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Job reconciliationJob(JobRepository jobRepository, Step reconciliationStep, Step ledgerIntegrityStep) {
        return new JobBuilder("reconciliationJob", jobRepository)
                .start(reconciliationStep)
                .next(ledgerIntegrityStep)
                .build();
    }
}
