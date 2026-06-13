package com.ibank.batch;

import com.ibank.domain.interest.repository.InterestAccrualRepository;
import com.ibank.domain.interest.repository.dto.UnpaidInterest;
import com.ibank.domain.interest.service.InterestPaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.List;

/**
 * 월말 이자 지급 배치. 미지급 적립분이 있는 계좌(조회) → 계좌별로 합산 지급.
 *
 * <p>지급은 계좌마다 {@link InterestPaymentService#payAccount} 한 번 = 독립 트랜잭션(REQUIRES_NEW)으로
 * 처리한다. 계좌의 비관적 락 안에서 미지급분을 다시 읽어 합산하므로 배치 재실행·중복 실행에도
 * 같은 적립분을 두 번 지급하지 않는다(멱등). 입금·원장 분개·적립분 paid 표시가 한 트랜잭션으로 묶인다.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class InterestPaymentBatchConfig {

    private final InterestAccrualRepository interestAccrualRepository;
    private final InterestPaymentService interestPaymentService;

    @Bean
    public Step interestPaymentStep(JobRepository jobRepository,
                                    PlatformTransactionManager transactionManager) {
        return new StepBuilder("interestPaymentStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    List<UnpaidInterest> targets = interestAccrualRepository.findUnpaidGroupedByAccount();
                    int paidAccounts = 0;
                    BigDecimal paidTotal = BigDecimal.ZERO;
                    for (UnpaidInterest target : targets) {
                        // 각 계좌는 독립 트랜잭션(REQUIRES_NEW)으로 지급된다. 한 계좌 실패가 나머지를 막지 않는다.
                        BigDecimal paid = interestPaymentService.payAccount(target.getAccountId());
                        if (paid.signum() > 0) {
                            paidAccounts++;
                            paidTotal = paidTotal.add(paid);
                        }
                    }
                    log.info("이자 지급 배치 완료 — 계좌 {}건, 총 {}원 지급", paidAccounts, paidTotal);
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Job interestPaymentJob(JobRepository jobRepository, Step interestPaymentStep) {
        return new JobBuilder("interestPaymentJob", jobRepository)
                .start(interestPaymentStep)
                .build();
    }
}
