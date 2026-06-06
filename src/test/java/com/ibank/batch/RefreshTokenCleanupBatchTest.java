package com.ibank.batch;

import com.ibank.domain.user.entity.RefreshToken;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.RefreshTokenRepository;
import com.ibank.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리프레시 토큰 정리 배치 검증. 만료 후 보존 기간(retention-days, 기본 7일)이 지난 토큰만 삭제되고,
 * 만료되지 않았거나 유예 기간 내인 토큰은 보존되는지 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RefreshTokenCleanupBatchTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JobLauncher jobLauncher;
    @Autowired Job refreshTokenCleanupJob;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        user = userRepository.save(User.builder()
                .loginId("cleanup-user").password("x").name("정리유저")
                .email("cleanup@test.com").role(User.UserRole.ROLE_USER).build());
    }

    private void saveToken(String hash, LocalDateTime expiresAt, boolean rotated) {
        RefreshToken token = RefreshToken.builder()
                .user(user).tokenHash(hash).expiresAt(expiresAt).build();
        if (rotated) {
            token.markRotated();
        }
        refreshTokenRepository.save(token);
    }

    @Test
    @DisplayName("정리 배치 - 만료 후 보존 기간이 지난 토큰만 삭제하고 나머지는 보존한다")
    void cleanupJob_deletesOnlyExpiredBeyondRetention() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        saveToken("old-expired", now.minusDays(10), false);           // 만료 10일 경과 → 삭제
        saveToken("old-expired-rotated", now.minusDays(10), true);    // 만료된 회전 토큰도 삭제
        saveToken("recent-expired", now.minusDays(1), false);         // 만료됐으나 유예(7일) 내 → 보존
        saveToken("active", now.plusDays(5), false);                  // 미만료 → 보존

        JobExecution execution = jobLauncher.run(refreshTokenCleanupJob, new JobParametersBuilder()
                .addLong("runAt", System.currentTimeMillis())
                .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<String> remaining = refreshTokenRepository.findAll().stream()
                .map(RefreshToken::getTokenHash).toList();
        assertThat(remaining).containsExactlyInAnyOrder("recent-expired", "active");
    }
}
