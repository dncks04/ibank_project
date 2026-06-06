package com.ibank.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰 정리 배치 스케줄러. 기본 비활성.
 * 운영에서 ibank.batch.refresh-token-cleanup.enabled=true 로 켠다.
 */
@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "ibank.batch.refresh-token-cleanup.enabled", havingValue = "true")
@RequiredArgsConstructor
public class RefreshTokenCleanupScheduler {

    private final JobLauncher jobLauncher;
    private final Job refreshTokenCleanupJob;

    /** 매일 새벽 3시 30분(기본) 정리 배치 실행. 정산 배치(2시)와 시간대를 분리한다. */
    @Scheduled(cron = "${ibank.batch.refresh-token-cleanup.cron:0 30 3 * * *}")
    public void runDailyCleanup() throws Exception {
        jobLauncher.run(refreshTokenCleanupJob, new JobParametersBuilder()
                .addLong("runAt", System.currentTimeMillis())
                .toJobParameters());
        log.info("리프레시 토큰 정리 배치 실행 트리거됨");
    }
}
