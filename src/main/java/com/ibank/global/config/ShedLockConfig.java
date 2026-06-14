package com.ibank.global.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * 배치 스케줄러 분산 락(ShedLock) 설정.
 *
 * <p>다중 인스턴스로 배포하면 모든 노드의 {@code @Scheduled} 메서드가 동시에 트리거되어
 * 같은 배치(정산·이자 지급 등)가 중복 실행될 수 있다. ShedLock은 {@code shedlock} 테이블의
 * 행을 락 이름으로 점유해, 락을 잡은 한 노드만 실행하고 나머지는 건너뛰게 한다.
 *
 * <p>{@code defaultLockAtMostFor}: 노드가 락 해제 전에 죽어도 이 시간이 지나면 락이 풀려
 * 다른 노드가 이어받는다(데드락 방지). 개별 잡은 {@code @SchedulerLock}으로 값을 덮어쓴다.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime() // 노드 간 시계 차이 영향을 없애기 위해 DB 시간 사용
                        .build());
    }
}
