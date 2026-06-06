package com.ibank.concurrency;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 비관적 락 대기의 fail-fast 검증.
 *
 * <p>한 트랜잭션이 행을 {@code FOR UPDATE}로 쥔 상태에서, 같은 행을 락하려는 후속 트랜잭션이
 * 무한 대기하지 않고 DB의 {@code lock_timeout} 안에 실패하는지 확인한다. 설정이 없으면
 * 멈춘 트랜잭션이 행을 쥐는 한 후속 이체는 영원히 대기한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PessimisticLockTimeoutTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;

    private static final String ACC = "20260101000099";

    @BeforeEach
    void setUp() {
        accountRepository.deleteAll();
        userRepository.deleteAll();
        User user = userRepository.save(User.builder()
                .loginId("lock-timeout-user")
                .password(passwordEncoder.encode("pass"))
                .name("락").email("lock@test.com")
                .role(User.UserRole.ROLE_USER).build());
        accountRepository.save(Account.builder()
                .accountNumber(ACC).owner(user)
                .initialBalance(new BigDecimal("100000")).build());
    }

    @AfterEach
    void tearDown() {
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("행 락이 점유된 동안 후속 락 요청은 무한 대기 없이 lock_timeout 내에 실패한다")
    void rowLockHeld_subsequentLockFailsFast() throws InterruptedException {
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();

        // 보유 스레드: 행을 FOR UPDATE로 쥐고 release 신호까지 트랜잭션을 유지한다.
        Future<?> held = holder.submit(() -> txTemplate.executeWithoutResult(status -> {
            accountRepository.findByAccountNumberWithLock(ACC).orElseThrow();
            lockHeld.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        assertThat(lockHeld.await(5, TimeUnit.SECONDS)).as("보유 스레드가 락을 잡아야 한다").isTrue();

        // 후속 락 요청: 무한 대기가 아니라 lock_timeout 내에 실패해야 한다.
        long start = System.nanoTime();
        Throwable thrown = catchThrowable(() -> txTemplate.executeWithoutResult(status ->
                accountRepository.findByAccountNumberWithLock(ACC).orElseThrow()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        release.countDown();
        holder.shutdown();
        assertThat(holder.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // 락 충돌로 실패해야 한다(즉시 다른 이유로 실패한 것이 아님 → 실제 대기 후 타임아웃)
        assertThat(thrown).isInstanceOf(PessimisticLockingFailureException.class);
        assertThat(elapsedMs)
                .as("락을 실제로 대기한 뒤(>1.5s) 무한 대기 없이(<12s) 실패해야 한다")
                .isBetween(1_500L, 12_000L);
        // 락이 풀린 뒤에는 정상 획득된다(타임아웃이 영구적 손상을 남기지 않음)
        txTemplate.executeWithoutResult(status ->
                accountRepository.findByAccountNumberWithLock(ACC).orElseThrow());
    }
}
