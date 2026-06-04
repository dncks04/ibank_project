package com.ibank.concurrency;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DepositConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private TransferService transferService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String ACC = "20260102000001";

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(User.builder()
                .loginId("deposit-test-user")
                .password(passwordEncoder.encode("pass"))
                .name("입금테스터").email("deposit@test.com")
                .role(User.UserRole.ROLE_USER).build());

        accountRepository.save(Account.builder()
                .accountNumber(ACC).owner(user)
                .initialBalance(BigDecimal.ZERO).build());
    }

    @AfterEach
    void tearDown() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("동시 입금 - 낙관적 락 재시도로 잔액이 정확히 반영된다")
    void deposit_concurrent_balanceIsAccurate() throws InterruptedException {
        int threadCount = 10;
        BigDecimal amount = new BigDecimal("1000");
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferService.deposit(ACC, amount, UUID.randomUUID().toString());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // 재시도 소진 시 예외 발생 가능 — 실패 자체는 예상된 동작
                    failCount.incrementAndGet();
                } catch (Throwable t) {
                    unexpectedErrors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Account account = accountRepository.findByAccountNumber(ACC).orElseThrow();

        // 예상치 못한 오류는 없어야 한다
        assertThat(unexpectedErrors).isEmpty();
        // 잔액 = 성공 횟수 × 입금액 (일관성 보장)
        assertThat(account.getBalance())
                .isEqualByComparingTo(amount.multiply(BigDecimal.valueOf(successCount.get())));
        // 성공 + 실패 = 전체 스레드 수
        assertThat(successCount.get() + failCount.get()).isEqualTo(threadCount);
        // DB 트랜잭션 레코드 수 = 성공 횟수
        assertThat(transactionRepository.count()).isEqualTo(successCount.get());
    }

    @Test
    @DisplayName("동시 입금 - 잔액이 절대 음수가 되지 않는다")
    void deposit_concurrent_balanceNeverNegative() throws InterruptedException {
        int threadCount = 20;
        BigDecimal amount = new BigDecimal("500");

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferService.deposit(ACC, amount, UUID.randomUUID().toString());
                } catch (Exception ignored) {
                    // 실패는 허용
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Account account = accountRepository.findByAccountNumber(ACC).orElseThrow();

        // 입금만 하므로 잔액은 항상 0 이상
        assertThat(account.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        // 잔액은 실제 성공 트랜잭션 합산과 일치해야 한다
        long txCount = transactionRepository.count();
        assertThat(account.getBalance())
                .isEqualByComparingTo(amount.multiply(BigDecimal.valueOf(txCount)));
    }
}
