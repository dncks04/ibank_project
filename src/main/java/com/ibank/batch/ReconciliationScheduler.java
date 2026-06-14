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

/**
 * 정산 배치 스케줄러. 기본 비활성(테스트/로컬에서 자동 실행되지 않음).
 * 운영에서 ibank.batch.reconciliation.enabled=true 로 켠다.
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "ibank.batch.reconciliation.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ReconciliationScheduler {

    private final JobLauncher jobLauncher;
    private final Job reconciliationJob;

    /** 매일 새벽 2시(기본) 정산 배치 실행. runAt 파라미터로 매 실행을 새 JobInstance로 만든다. */
    @Scheduled(cron = "${ibank.batch.reconciliation.cron:0 0 2 * * *}")
    @SchedulerLock(name = "reconciliation", lockAtMostFor = "PT25M", lockAtLeastFor = "PT1M")
    public void runDailyReconciliation() throws Exception {
        jobLauncher.run(reconciliationJob, new JobParametersBuilder()
                .addLong("runAt", System.currentTimeMillis())
                .toJobParameters());
        log.info("일일 정산 배치 실행 트리거됨");
    }
}
