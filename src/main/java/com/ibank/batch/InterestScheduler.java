package com.ibank.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 이자 적립·지급 스케줄러. 기본 비활성(테스트/로컬에서 자동 실행되지 않음).
 * 운영에서 ibank.batch.interest.enabled=true 로 켠다.
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "ibank.batch.interest.enabled", havingValue = "true")
@RequiredArgsConstructor
public class InterestScheduler {

    private final JobLauncher jobLauncher;
    private final Job interestAccrualJob;
    private final Job interestPaymentJob;

    /** 매일 새벽 1시(기본): 전일자 기준 일일 이자 적립. accrualDate를 파라미터로 매 실행을 새 JobInstance로 만든다. */
    @Scheduled(cron = "${ibank.batch.interest.accrual-cron:0 0 1 * * *}")
    @SchedulerLock(name = "interestAccrual", lockAtMostFor = "PT25M", lockAtLeastFor = "PT1M")
    public void runDailyAccrual() throws Exception {
        LocalDate accrualDate = LocalDate.now().minusDays(1);
        jobLauncher.run(interestAccrualJob, new JobParametersBuilder()
                .addString("accrualDate", accrualDate.toString())
                .addLong("runAt", System.currentTimeMillis())
                .toJobParameters());
        log.info("일일 이자 적립 배치 트리거됨 (accrualDate={})", accrualDate);
    }

    /** 매월 1일 새벽 4시(기본): 전월 적립분 일괄 지급. */
    @Scheduled(cron = "${ibank.batch.interest.payment-cron:0 0 4 1 * *}")
    @SchedulerLock(name = "interestPayment", lockAtMostFor = "PT25M", lockAtLeastFor = "PT1M")
    public void runMonthlyPayment() throws Exception {
        jobLauncher.run(interestPaymentJob, new JobParametersBuilder()
                .addLong("runAt", System.currentTimeMillis())
                .toJobParameters());
        log.info("월말 이자 지급 배치 트리거됨");
    }
}
