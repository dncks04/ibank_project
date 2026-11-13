package com.ibank.batch;

import com.ibank.domain.hold.service.HoldExpiryService;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 홀드 만료 스케줄러. 기본 비활성. 운영에서 ibank.holds.expiry.enabled=true 로 켠다.
 *
 * <p>판정 대기 거래 복구({@link PendingReviewRecoveryScheduler})와 한 계열이다. 복구가 PENDING_REVIEW 거래를
 * 분 단위로 마저 처리하고, 이 스케줄러는 그래도 남은 홀드와 보류(HELD)된 채 방치된 홀드를 최대 보류 시간에 푼다.
 * ShedLock으로 한 인스턴스만 실행한다(겹쳐도 거래 행 락에서 한 번만 적용되지만 불필요한 락 경합을 줄인다).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "ibank.holds.expiry.enabled", havingValue = "true")
@RequiredArgsConstructor
public class HoldExpiryScheduler {

    private final HoldExpiryService expiryService;

    @Scheduled(fixedDelayString = "${ibank.holds.expiry.interval:1m}")
    @SchedulerLock(name = "holdExpiry", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    public void expire() {
        expiryService.expireOverdue();
    }
}
