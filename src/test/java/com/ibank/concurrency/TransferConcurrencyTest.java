package com.ibank.concurrency;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
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
class TransferConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private TransferService transferService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String ACC_A = "20260101000001";
    private static final String ACC_B = "20260101000002";

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(User.builder()
                .loginId("concurrency-user")
                .password(passwordEncoder.encode("pass"))
                .name("테스트").email("concurrency@test.com")
                .role(User.UserRole.ROLE_USER).build());

        accountRepository.save(Account.builder()
                .accountNumber(ACC_A).owner(user)
                .initialBalance(new BigDecimal("100000")).build());
        accountRepository.save(Account.builder()
                .accountNumber(ACC_B).owner(user)
                .initialBalance(BigDecimal.ZERO).build());
    }

    @AfterEach
    void tearDown() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("동시 이체 - 총 잔액이 보존된다 (비관적 락)")
    void transfer_concurrent_totalBalanceIsConserved() throws InterruptedException {
        int threadCount = 10;
        BigDecimal amount = new BigDecimal("1000");

        List<Throwable> errors = runConcurrently(threadCount, () ->
                transferService.transfer(new TransferRequest(
                        UUID.randomUUID().toString(), ACC_A, ACC_B, amount, "잔액보존테스트"))
        );

        Account a = accountRepository.findByAccountNumber(ACC_A).orElseThrow();
        Account b = accountRepository.findByAccountNumber(ACC_B).orElseThrow();

        // 총 잔액은 이체 전후 동일해야 한다
        assertThat(a.getBalance().add(b.getBalance()))
                .isEqualByComparingTo(new BigDecimal("100000"));
        // 비관적 락이므로 모든 스레드가 성공해야 한다
        assertThat(errors).isEmpty();
        // 잔액 차감이 정확히 10건 반영되어야 한다
        assertThat(a.getBalance()).isEqualByComparingTo(new BigDecimal("90000"));
        assertThat(b.getBalance()).isEqualByComparingTo(new BigDecimal("10000"));
    }

    @Test
    @DisplayName("A→B와 B→A 동시 이체 - 데드락 없이 완료된다")
    void transfer_concurrent_deadlockNotOccurred() throws InterruptedException {
        // 양방향 동시 이체: A→B 50건, B→A 50건 → 총 잔액 보존, 데드락 없음
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        User user = userRepository.findByLoginId("concurrency-user").orElseThrow();
        accountRepository.save(Account.builder()
                .accountNumber(ACC_A).owner(user).initialBalance(new BigDecimal("50000")).build());
        accountRepository.save(Account.builder()
                .accountNumber(ACC_B).owner(user).initialBalance(new BigDecimal("50000")).build());

        int pairs = 50;
        BigDecimal amount = new BigDecimal("100");
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(pairs * 2);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(pairs * 2);
        for (int i = 0; i < pairs; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferService.transfer(new TransferRequest(
                            UUID.randomUUID().toString(), ACC_A, ACC_B, amount, "A→B"));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferService.transfer(new TransferRequest(
                            UUID.randomUUID().toString(), ACC_B, ACC_A, amount, "B→A"));
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        // 데드락이 발생하면 이 대기가 타임아웃된다
        boolean completed = doneLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).as("60초 내 모든 이체가 완료되어야 한다 (데드락 없음)").isTrue();
        assertThat(errors).isEmpty();

        Account a = accountRepository.findByAccountNumber(ACC_A).orElseThrow();
        Account b = accountRepository.findByAccountNumber(ACC_B).orElseThrow();
        // A→B와 B→A가 쌍으로 완료되므로 순 잔액 변화 없음
        assertThat(a.getBalance().add(b.getBalance()))
                .isEqualByComparingTo(new BigDecimal("100000"));
    }

    @Test
    @DisplayName("같은 멱등성 키로 동시 이체 - 단 한 건만 처리된다")
    void transfer_sameIdempotencyKey_processedOnlyOnce() throws InterruptedException {
        int threadCount = 10;
        String sharedKey = UUID.randomUUID().toString();
        BigDecimal amount = new BigDecimal("1000");

        AtomicInteger successCount = new AtomicInteger();
        runConcurrently(threadCount, () -> {
            transferService.transfer(new TransferRequest(sharedKey, ACC_A, ACC_B, amount, "멱등성테스트"));
            successCount.incrementAndGet();
        });

        Account a = accountRepository.findByAccountNumber(ACC_A).orElseThrow();
        Account b = accountRepository.findByAccountNumber(ACC_B).orElseThrow();

        // 잔액: 정확히 1건만 이체되어야 한다
        assertThat(a.getBalance()).isEqualByComparingTo(new BigDecimal("99000"));
        assertThat(b.getBalance()).isEqualByComparingTo(new BigDecimal("1000"));
        // DB에 트랜잭션 레코드가 1건이어야 한다
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("잔액 초과 동시 이체 - 잔액이 음수가 되지 않는다")
    void transfer_concurrent_overdraftNeverOccurs() throws InterruptedException {
        // A 잔액 5,000원, 스레드 10개가 각각 1,000원 이체 시도 → 5개만 성공
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        User user = userRepository.findByLoginId("concurrency-user").orElseThrow();
        accountRepository.save(Account.builder()
                .accountNumber(ACC_A).owner(user).initialBalance(new BigDecimal("5000")).build());
        accountRepository.save(Account.builder()
                .accountNumber(ACC_B).owner(user).initialBalance(BigDecimal.ZERO).build());

        int threadCount = 10;
        BigDecimal amount = new BigDecimal("1000");
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    transferService.transfer(new TransferRequest(
                            UUID.randomUUID().toString(), ACC_A, ACC_B, amount, "잔액초과테스트"));
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        Account a = accountRepository.findByAccountNumber(ACC_A).orElseThrow();
        Account b = accountRepository.findByAccountNumber(ACC_B).orElseThrow();

        // 잔액이 절대 음수가 되어서는 안 된다
        assertThat(a.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        // 총 잔액 보존
        assertThat(a.getBalance().add(b.getBalance())).isEqualByComparingTo(new BigDecimal("5000"));
        // 정확히 5건 성공, 5건 실패
        assertThat(successCount.get()).isEqualTo(5);
        assertThat(failCount.get()).isEqualTo(5);
    }

    // --- 헬퍼 ---

    private List<Throwable> runConcurrently(int threadCount, ThrowingRunnable task) throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    task.run();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        return errors;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
