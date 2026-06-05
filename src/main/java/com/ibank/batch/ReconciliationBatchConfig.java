package com.ibank.batch;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.ledger.entity.LedgerDirection;
import com.ibank.domain.ledger.entity.ReconciliationResult;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.ledger.repository.ReconciliationResultRepository;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 일일 정산(reconciliation) 배치.
 *
 * 청크 지향 스텝: 계좌(reader) → 잔액 vs 원장 합계 비교(processor) → 결과 저장(writer).
 * 불변식 {@code balance == SUM(CREDIT) - SUM(DEBIT)} 위반 계좌를 찾아 reconciliation_results에 남긴다.
 */
@Configuration
@RequiredArgsConstructor
public class ReconciliationBatchConfig {

    private static final int CHUNK_SIZE = 100;

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final ReconciliationResultRepository reconciliationResultRepository;

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
        return account -> {
            BigDecimal ledgerSum = ledgerEntryRepository
                    .signedSumByAccountId(account.getId(), LedgerDirection.CREDIT);
            return ReconciliationResult.of(jobExecutionId, account.getId(), account.getBalance(), ledgerSum);
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

    @Bean
    public Job reconciliationJob(JobRepository jobRepository, Step reconciliationStep) {
        return new JobBuilder("reconciliationJob", jobRepository)
                .start(reconciliationStep)
                .build();
    }
}
