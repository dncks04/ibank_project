package com.ibank.batch;

import com.ibank.domain.outbox.OutboxMaintenanceService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 보관 기간이 지난 Outbox 이벤트·내부 API 멱등성 키 정리 스케줄러. 기본 비활성.
 * 운영에서 ibank.outbox.cleanup.enabled=true 로 켠다. ShedLock으로 한 인스턴스만 실행한다.
 *
 * <p>삭제는 한 문장으로 끝나는 짧은 트랜잭션이지만, 지울 행이 아주 많으면 그동안 쓰기 트랜잭션이 열려 있어
 * Outbox 공개가 그만큼 지연된다. 매일 돌려 한 번에 지울 양을 작게 유지한다.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "ibank.outbox.cleanup.enabled", havingValue = "true")
@RequiredArgsConstructor
public class OutboxCleanupScheduler {

    private final OutboxMaintenanceService maintenanceService;

    @Value("${ibank.outbox.retention:30d}")
    private Duration eventRetention;

    @Value("${ibank.internal.idempotency-key-retention:7d}")
    private Duration idempotencyKeyRetention;

    /** 매일 새벽 4시 30분(기본). 다른 배치(정산 2시, 토큰 정리 3시 30분)와 시간대를 나눈다. */
    @Scheduled(cron = "${ibank.outbox.cleanup.cron:0 30 4 * * *}")
    @SchedulerLock(name = "outboxCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void cleanup() {
        maintenanceService.purgePublishedEventsOlderThan(eventRetention);
        maintenanceService.purgeIdempotencyKeysOlderThan(idempotencyKeyRetention);
    }
}
