package com.ibank.batch;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.ledger.dto.AccountLedgerBalance;
import com.ibank.domain.ledger.entity.ReconciliationResult;
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

    @Bean
    public Job reconciliationJob(JobRepository jobRepository, Step reconciliationStep) {
        return new JobBuilder("reconciliationJob", jobRepository)
                .start(reconciliationStep)
                .build();
    }
}
