package com.ibank.concurrency;

import com.ibank.domain.user.dto.LoginRequest;
import com.ibank.domain.user.dto.LoginResponse;
import com.ibank.domain.user.dto.RegisterRequest;
import com.ibank.domain.user.dto.TokenResponse;
import com.ibank.domain.user.entity.RefreshToken;
import com.ibank.domain.user.exception.InvalidRefreshTokenException;
import com.ibank.domain.user.repository.RefreshTokenRepository;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.domain.user.service.UserService;
import com.ibank.global.audit.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 리프레시 토큰 회전의 동시성 정합성.
 *
 * <p>회전은 check-then-act(폐기 여부 확인 → 회전)이므로 행 락이 없으면 동일 토큰 동시 /refresh가
 * 둘 다 통과해 토큰 패밀리가 갈라진다. 비관적 쓰기 락으로 직렬화하고, 회전 직후 유예창 안의 재제출은
 * 정상 동시 재시도로 보아 전체 세션을 무효화하지 않음을 검증한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RefreshTokenRotationConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired AuditLogRepository auditLogRepository;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        userService.register(new RegisterRequest("rotateuser", "password123", "회전유저", "rotate@test.com"));
    }

    @Test
    @DisplayName("같은 유효 토큰으로 동시 /refresh - 단 한 번만 회전하고 세션은 유지된다")
    void concurrentRefresh_rotatesOnceAndKeepsSessionAlive() throws InterruptedException {
        LoginResponse login = userService.login(new LoginRequest("rotateuser", "password123"));
        String sharedToken = login.refreshToken();

        int threadCount = 8;
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        AtomicReference<TokenResponse> winner = new AtomicReference<>();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    TokenResponse rotated = userService.refresh(sharedToken);
                    winner.set(rotated);
                    successCount.incrementAndGet();
                } catch (InvalidRefreshTokenException e) {
                    rejectedCount.incrementAndGet(); // 유예창 내 동시 재시도의 정상 거부
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // 예기치 못한 예외(데드락/락 타임아웃 등)는 없어야 한다
        assertThat(unexpected).isEmpty();
        // 정확히 한 스레드만 회전에 성공한다(토큰 패밀리 분기 없음)
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(rejectedCount.get()).isEqualTo(threadCount - 1);

        // 세션이 살아있다: 승자가 받은 새 토큰은 여전히 사용 가능하다(오탐 무효화 없음)
        assertThatCode(() -> userService.refresh(winner.get().refreshToken()))
                .doesNotThrowAnyException();

        // 유예창 내 재시도는 탈취가 아니므로 재사용 경보(REFRESH_TOKEN_REUSE)를 남기지 않는다
        assertThat(auditLogRepository.findAll())
                .noneMatch(a -> "REFRESH_TOKEN_REUSE".equals(a.getAction()));

        // 폐기되지 않은(활성) 리프레시 토큰이 존재한다 — 전체 세션 무효화가 일어나지 않았다
        assertThat(refreshTokenRepository.findAll()).anyMatch(t -> !t.isRevoked());
    }

    @Test
    @DisplayName("동시 회전 후에도 활성 토큰은 정확히 하나만 남는다")
    void concurrentRefresh_leavesExactlyOneActiveToken() throws InterruptedException {
        LoginResponse login = userService.login(new LoginRequest("rotateuser", "password123"));
        String sharedToken = login.refreshToken();

        int threadCount = 8;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    userService.refresh(sharedToken);
                } catch (InvalidRefreshTokenException ignored) {
                    // 유예창 내 동시 재시도의 정상 거부
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // 원본 1개가 회전되어 후속 1개만 발급되므로 총 2개, 그중 활성은 정확히 1개
        long active = refreshTokenRepository.findAll().stream().filter(t -> !t.isRevoked()).count();
        assertThat(active).isEqualTo(1);
        assertThat(refreshTokenRepository.count()).isEqualTo(2);
    }
}
