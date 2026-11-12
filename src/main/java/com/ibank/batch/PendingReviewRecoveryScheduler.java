package com.ibank.batch;

import com.ibank.domain.transaction.service.PendingReviewRecoveryService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 판정 대기(PENDING_REVIEW)로 남은 거래의 복구 스케줄러. 기본 비활성.
 * 운영에서 ibank.fds.recovery.enabled=true 로 켠다.
 *
 * <p>ShedLock으로 한 인스턴스만 실행한다. 겹쳐 돌아도 확정 단계가 거래 상태를 확인하므로 결과는 같지만,
 * 같은 거래의 판정을 FDS 서비스에 여러 번 묻는 낭비를 줄인다.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "ibank.fds.recovery.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PendingReviewRecoveryScheduler {

    private final PendingReviewRecoveryService recoveryService;

    @Scheduled(fixedDelayString = "${ibank.fds.recovery.interval:30s}")
    @SchedulerLock(name = "pendingReviewRecovery", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void recover() {
        recoveryService.recoverStale();
    }
}
