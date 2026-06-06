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

    @Test
    @DisplayName("IP 실패가 IP 임계치에 도달하면 IP가 잠긴다")
    void ipLocksAfterMaxIpAttempts() {
        LoginAttemptService service = new LoginAttemptService();

        for (int i = 0; i < LoginAttemptService.MAX_IP_ATTEMPTS; i++) {
            assertThat(service.isIpLocked("1.2.3.4")).isFalse();
            service.recordIpFailure("1.2.3.4");
        }

        assertThat(service.isIpLocked("1.2.3.4")).isTrue();
        // 다른 IP는 격리된다
        assertThat(service.isIpLocked("5.6.7.8")).isFalse();
    }

    @Test
    @DisplayName("계정 잠금과 IP 잠금은 서로 독립적이며 IP 임계치가 더 높다")
    void accountAndIpLocksAreIndependent() {
        LoginAttemptService service = new LoginAttemptService();

        // 한 IP에서 계정 임계치만큼만 IP 실패를 기록 → 계정 임계치 < IP 임계치이므로 IP는 아직 안 잠김
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            service.recordIpFailure("9.9.9.9");
        }
        assertThat(service.isIpLocked("9.9.9.9")).isFalse();
        // 계정 키는 IP 키와 분리되어 영향받지 않는다
        assertThat(service.isLocked("9.9.9.9")).isFalse();
    }
}
