package com.ibank.batch;

import com.ibank.domain.user.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;

/**
 * 리프레시 토큰 정리 배치.
 *
 * <p>폐기·만료된 토큰이 무한 증가하지 않도록 만료 후 보존 기간이 지난 행을 삭제한다.
 * 만료된 행만 지우므로 회전된 토큰은 만료 전까지 재사용 탐지 tripwire로 남는다.
 * 단순 일괄 삭제이므로 chunk가 아닌 tasklet 스텝으로 구성한다(정산 배치와 같은 배치 인프라 재사용).
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RefreshTokenCleanupBatchConfig {

    private final RefreshTokenRepository refreshTokenRepository;

    /** 만료 후 이 일수가 지난 토큰을 삭제 대상으로 본다(포렌식/감사용 유예). */
    @Value("${ibank.batch.refresh-token-cleanup.retention-days:7}")
    private long retentionDays;

    @Bean
    public Step refreshTokenCleanupStep(JobRepository jobRepository,
                                        PlatformTransactionManager transactionManager) {
        return new StepBuilder("refreshTokenCleanupStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);
                    int deleted = refreshTokenRepository.deleteByExpiresAtBefore(threshold);
                    contribution.incrementWriteCount(deleted);
                    log.info("리프레시 토큰 정리: 만료 후 {}일 경과 {}건 삭제 (threshold={})",
                            retentionDays, deleted, threshold);
                    return RepeatStatus.FINISHED;
                }, transactionManager)
                .build();
    }

    @Bean
    public Job refreshTokenCleanupJob(JobRepository jobRepository, Step refreshTokenCleanupStep) {
        return new JobBuilder("refreshTokenCleanupJob", jobRepository)
                .start(refreshTokenCleanupStep)
                .build();
    }
}
