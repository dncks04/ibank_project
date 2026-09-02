package com.ibank.concurrency;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.ledger.service.ReconciliationService;
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
    @Autowired private AccountService accountService;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String ACC_A = "20260101000001";
    private static final String ACC_B = "20260101000002";

    private Long userId;

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
        userId = user.getId();

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
                transferService.transfer(userId, new TransferRequest(
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
    @DisplayName("A→B와 B→A 양방향 이체 - 데드락 없이 완료되고 정합성이 유지된다")
    void transfer_concurrent_deadlockNotOccurred() throws InterruptedException {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        // 정식 개설 경로로 만든다. accountRepository.save()로 만들면 초기 잔액의 원장 leg가 남지 않아
        // "잔액 == 원장 합계" 검사가 실제와 무관한 불일치를 낸다.
        String accA = openAccount(new BigDecimal("50000"));
        String accB = openAccount(new BigDecimal("50000"));

        // 동시 스레드 수를 커넥션 풀(20) 아래로 둔다. 풀을 넘기면 커넥션 획득 타임아웃이 섞여
        // 데드락 여부가 자원 포화에 가려진다. 포화 상황의 거동은 TransferPoolSaturationInvariantTest가 본다.
        // 스레드를 줄인 만큼 라운드를 늘려, 같은 두 계좌에 대한 행 락 경합 강도는 그대로 유지한다.
        int threads = 16;
        int rounds = 20;
        BigDecimal amount = new BigDecimal("100");
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            // 절반은 A→B, 절반은 B→A. 두 방향이 같은 두 행을 반대 순서로 요구하므로
            // 락을 계좌번호 순으로 잡지 않으면 여기서 데드락이 난다.
            boolean forward = (i % 2 == 0);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int r = 0; r < rounds; r++) {
                        String from = forward ? accA : accB;
                        String to = forward ? accB : accA;
                        transferService.transfer(userId, new TransferRequest(
                                UUID.randomUUID().toString(), from, to, amount,
                                forward ? "A→B" : "B→A"));
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        // 데드락이 발생하면 이 대기가 타임아웃된다
        boolean completed = doneLatch.await(120, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).as("모든 이체가 제한 시간 내 완료되어야 한다 (데드락 없음)").isTrue();

        // 정합성 단언을 오류 검사보다 먼저 한다. 순서를 반대로 두면 오류가 하나라도 생겼을 때
        // 여기서 멈춰, 정작 확인해야 할 잔액·원장이 검사조차 되지 않는다.
        assertInvariantsHold(accA, accB, new BigDecimal("100000"));

        // 풀 안에서 도는 경합이므로 커넥션 획득 실패가 없어야 하고, 락 타임아웃도 없어야 한다.
        assertThat(errors).as("경합만으로는 어떤 이체도 실패하지 않아야 한다").isEmpty();
    }

    /**
     * 잔액과 원장이 함께 성립하는지 확인한다.
     * 계좌 층위(잔액 == CREDIT합 − DEBIT합)와 시스템 층위(분개 zero-sum, 시산표 0)를 모두 본다.
     */
    private void assertInvariantsHold(String accA, String accB, BigDecimal expectedTotal) {
        Account a = accountRepository.findByAccountNumber(accA).orElseThrow();
        Account b = accountRepository.findByAccountNumber(accB).orElseThrow();

        assertThat(a.getBalance().add(b.getBalance()))
                .as("총 잔액 보존 — 돈이 생기거나 사라지면 안 된다")
                .isEqualByComparingTo(expectedTotal);
        assertThat(a.getBalance()).as("A 잔액 음수 불가").isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(b.getBalance()).as("B 잔액 음수 불가").isGreaterThanOrEqualTo(BigDecimal.ZERO);

        assertThat(reconciliationService.findInconsistentAccounts())
                .as("계좌별 잔액 == 원장 합계").isEmpty();
        assertThat(reconciliationService.findUnbalancedJournals())
                .as("모든 분개의 차변/대변 합이 0").isEmpty();
        assertThat(reconciliationService.trialBalance())
                .as("전 원장 시산표 == 0").isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** 초기 잔액의 원장 leg까지 남기는 정식 개설 경로. 생성된 계좌번호를 돌려준다. */
    private String openAccount(BigDecimal initialBalance) {
        return accountService.openAccount(userId,
                new AccountOpenRequest(UUID.randomUUID().toString(), initialBalance)).accountNumber();
    }

    @Test
    @DisplayName("같은 멱등성 키로 동시 이체 - 단 한 건만 처리된다")
    void transfer_sameIdempotencyKey_processedOnlyOnce() throws InterruptedException {
        int threadCount = 10;
        String sharedKey = UUID.randomUUID().toString();
        BigDecimal amount = new BigDecimal("1000");

        AtomicInteger successCount = new AtomicInteger();
        runConcurrently(threadCount, () -> {
            transferService.transfer(userId, new TransferRequest(sharedKey, ACC_A, ACC_B, amount, "멱등성테스트"));
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
                    transferService.transfer(userId, new TransferRequest(
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
