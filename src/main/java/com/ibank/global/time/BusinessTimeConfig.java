package com.ibank.global.time;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 영업 타임존 설정. {@code ibank.business.zone}(기본 Asia/Seoul)이 업무 날짜 경계를 정한다.
 * JVM 기본 타임존은 건드리지 않는다 — {@link BusinessClock} 주석 참고.
 */
@Configuration
public class BusinessTimeConfig {

    /** 테스트가 고정 시계로 바꿔 끼울 수 있도록 빈으로 둔다. */
    @Bean
    @ConditionalOnMissingBean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    public BusinessClock businessClock(Clock clock,
                                       @Value("${ibank.business.zone:Asia/Seoul}") ZoneId businessZone) {
        return new BusinessClock(clock, businessZone, ZoneId.systemDefault());
    }
}
