package com.ibank.batch;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 분산 락(ShedLock) 검증. 한 노드가 락을 점유하면 같은 이름의 락은 다른 시도에서 잡히지 않고
 * (= 다중 인스턴스에서 배치 중복 실행 차단), 해제 후 다시 잡히는지 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ShedLockTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LockProvider lockProvider;
    @Autowired JdbcTemplate jdbcTemplate;

    private LockConfiguration config(String name) {
        return new LockConfiguration(Instant.now(), name, Duration.ofMinutes(5), Duration.ZERO);
    }

    @Test
    @DisplayName("Flyway가 shedlock 테이블을 생성한다")
    void shedlockTableExists() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'shedlock'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 이름의 락은 점유 중이면 다른 시도에서 잡히지 않고, 해제하면 다시 잡힌다")
    void lockIsMutuallyExclusive() {
        // 첫 번째 노드가 락 점유
        Optional<SimpleLock> first = lockProvider.lock(config("reconciliation"));
        assertThat(first).isPresent();

        // 점유 중에는 두 번째 노드가 같은 락을 잡지 못함 → 배치 중복 실행 차단
        Optional<SimpleLock> second = lockProvider.lock(config("reconciliation"));
        assertThat(second).isEmpty();

        // 해제 후에는 다시 잡힘
        first.get().unlock();
        Optional<SimpleLock> third = lockProvider.lock(config("reconciliation"));
        assertThat(third).isPresent();
        third.get().unlock();
    }
}
