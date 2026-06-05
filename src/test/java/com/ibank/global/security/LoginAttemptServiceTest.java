package com.ibank.global.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    @Test
    @DisplayName("실패가 임계치에 도달하면 잠긴다")
    void locksAfterMaxAttempts() {
        LoginAttemptService service = new LoginAttemptService();

        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            assertThat(service.isLocked("user")).isFalse();
            service.recordFailure("user");
        }

        assertThat(service.isLocked("user")).isTrue();
    }

    @Test
    @DisplayName("성공(reset) 시 카운터가 초기화되어 잠금이 풀린다")
    void resetClearsAttempts() {
        LoginAttemptService service = new LoginAttemptService();

        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            service.recordFailure("user");
        }
        assertThat(service.isLocked("user")).isTrue();

        service.reset("user");

        assertThat(service.isLocked("user")).isFalse();
    }

    @Test
    @DisplayName("서로 다른 사용자의 실패는 격리된다")
    void attemptsAreIsolatedPerUser() {
        LoginAttemptService service = new LoginAttemptService();

        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            service.recordFailure("victim");
        }

        assertThat(service.isLocked("victim")).isTrue();
        assertThat(service.isLocked("other")).isFalse();
    }
}
