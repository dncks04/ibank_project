package com.ibank.batch;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.interest.entity.InterestAccrual;
import com.ibank.domain.interest.repository.InterestAccrualRepository;
import com.ibank.global.config.InterestProperties;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

/**
 * 일일 이자 적립 배치. 전 계좌(reader) → 그날 이자 계산(processor) → 적립 행 저장(writer).
 *
 * <p>일할 이자 = {@code 잔액 × 연이율 / 일수기준}, 은행 관행대로 HALF_EVEN(banker's rounding)으로
 * 원 단위(scale=2) 반올림한다. ACTIVE 계좌만 적립하고, 같은 날 이미 적립된 계좌는 건너뛴다.
 * (account_id, accrual_date) 유니크 제약이 최종 멱등성 방어선이다.
 */
@Configuration
@EnableConfigurationProperties(InterestProperties.class)
@RequiredArgsConstructor
public class InterestAccrualBatchConfig {

    private static final int CHUNK_SIZE = 100;

    private final AccountRepository accountRepository;
    private final InterestAccrualRepository interestAccrualRepository;
    private final InterestProperties interestProperties;

    @Bean
    public RepositoryItemReader<Account> interestAccrualReader() {
        return new RepositoryItemReaderBuilder<Account>()
                .name("interestAccrualReader")
                .repository(accountRepository)
                .methodName("findAll")
                .pageSize(CHUNK_SIZE)
                .sorts(Map.of("id", Sort.Direction.ASC))
                .build();
    }

    @Bean
    @StepScope
    public ItemProcessor<Account, InterestAccrual> interestAccrualProcessor(
            @Value("#{jobParameters['accrualDate']}") String accrualDateParam) {
        LocalDate accrualDate = (accrualDateParam != null) ? LocalDate.parse(accrualDateParam) : LocalDate.now();
        BigDecimal annualRate = interestProperties.annualRate();
        BigDecimal basis = BigDecimal.valueOf(interestProperties.dayCountBasis());
        return account -> {
            if (account.getStatus() != Account.AccountStatus.ACTIVE) {
                return null; // 정지/해지 계좌는 적립 대상 아님
            }
            if (interestAccrualRepository.existsByAccountIdAndAccrualDate(account.getId(), accrualDate)) {
                return null; // 같은 날 이미 적립됨 → 멱등 skip
            }
            BigDecimal interest = account.getBalance()
                    .multiply(annualRate)
                    .divide(basis, 2, RoundingMode.HALF_EVEN);
            return InterestAccrual.of(account.getId(), accrualDate, account.getBalance(), annualRate, interest);
        };
    }

    @Bean
    public RepositoryItemWriter<InterestAccrual> interestAccrualWriter() {
        return new RepositoryItemWriterBuilder<InterestAccrual>()
                .repository(interestAccrualRepository)
                .methodName("save")
                .build();
    }

    @Bean
    public Step interestAccrualStep(JobRepository jobRepository,
                                    PlatformTransactionManager transactionManager,
                                    RepositoryItemReader<Account> interestAccrualReader,
                                    ItemProcessor<Account, InterestAccrual> interestAccrualProcessor,
                                    RepositoryItemWriter<InterestAccrual> interestAccrualWriter) {
        return new StepBuilder("interestAccrualStep", jobRepository)
                .<Account, InterestAccrual>chunk(CHUNK_SIZE, transactionManager)
                .reader(interestAccrualReader)
                .processor(interestAccrualProcessor)
                .writer(interestAccrualWriter)
                .build();
    }

    @Bean
    public Job interestAccrualJob(JobRepository jobRepository, Step interestAccrualStep) {
        return new JobBuilder("interestAccrualJob", jobRepository)
                .start(interestAccrualStep)
                .build();
    }
}
