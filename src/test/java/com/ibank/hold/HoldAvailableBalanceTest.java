package com.ibank.hold;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.dto.AccountResponse;
import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.entity.InsufficientBalanceException;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.hold.entity.AccountHold;
import com.ibank.domain.hold.repository.AccountHoldRepository;
import com.ibank.domain.hold.service.HoldService;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.ledger.service.ReconciliationService;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 홀드와 가용잔액.
 *
 * <p>홀드는 원장을 건드리지 않고 가용잔액만 줄인다. 출금·이체는 가용잔액 기준으로 검증되므로
 * 묶인 금액을 다른 출금으로 빼 갈 수 없어야 하고, 홀드 생성·해제·출금이 동시에 섞여도
 * 가용잔액이 음수가 되는 순간이 없어야 한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class HoldAvailableBalanceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private TransferService transferService;
    @Autowired private AccountService accountService;
    @Autowired private HoldService holdService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountHoldRepository holdRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private TransactionTemplate tx;
    private Long userId;
    private String accA;
    private String accB;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        tx = new TransactionTemplate(transactionManager);

        User user = userRepository.save(User.builder()
                .loginId("hold-user").password("x").name("홀드유저")
                .email("hold@test.com").role(User.UserRole.ROLE_USER).build());
        userId = user.getId();

        accA = open(new BigDecimal("10000"));
        accB = open(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("홀드를 걸면 가용잔액만 줄고 원장잔액과 원장은 그대로다")
    void hold_reducesAvailableOnly() {
        long ledgerBefore = ledgerEntryRepository.count();

        placeHold(accA, new BigDecimal("6000"));

        AccountResponse view = accountService.getAccount(accA, userId);
        assertThat(view.balance()).isEqualByComparingTo("10000");
        assertThat(view.heldAmount()).isEqualByComparingTo("6000");
        assertThat(view.availableBalance()).isEqualByComparingTo("4000");

        // 분개가 생기지 않았고, 잔액 == 원장 합계 불변식도 그대로 성립한다
        assertThat(ledgerEntryRepository.count()).isEqualTo(ledgerBefore);
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();
        assertThat(reconciliationService.trialBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("계좌 목록에도 계좌별 가용잔액과 홀드 금액이 실린다")
    void accountList_showsAvailablePerAccount() {
        placeHold(accA, new BigDecimal("2500"));

        List<AccountResponse> accounts = accountService.getMyAccounts(userId);

        AccountResponse a = accounts.stream().filter(r -> r.accountNumber().equals(accA)).findFirst().orElseThrow();
        AccountResponse b = accounts.stream().filter(r -> r.accountNumber().equals(accB)).findFirst().orElseThrow();
        assertThat(a.heldAmount()).isEqualByComparingTo("2500");
        assertThat(a.availableBalance()).isEqualByComparingTo("7500");
        assertThat(b.heldAmount()).isEqualByComparingTo("0");
        assertThat(b.availableBalance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("묶인 금액은 출금으로 빼 갈 수 없다 (가용잔액까지만 출금된다)")
    void withdraw_cannotTouchHeldAmount() {
        placeHold(accA, new BigDecimal("6000"));

        assertThatThrownBy(() -> transferService.withdraw(userId, withdraw(accA, "4001")))
                .isInstanceOf(InsufficientBalanceException.class);

        transferService.withdraw(userId, withdraw(accA, "4000")); // 가용잔액과 정확히 같은 금액은 허용

        assertThatThrownBy(() -> transferService.withdraw(userId, withdraw(accA, "1")))
                .isInstanceOf(InsufficientBalanceException.class);
        assertThat(balanceOf(accA)).isEqualByComparingTo("6000");
        assertThat(availableOf(accA)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("묶인 금액은 이체로도 빼 갈 수 없다")
    void transfer_cannotTouchHeldAmount() {
        placeHold(accA, new BigDecimal("6000"));

        assertThatThrownBy(() -> transferService.transfer(userId, transfer(accA, accB, "4001")))
                .isInstanceOf(InsufficientBalanceException.class);

        transferService.transfer(userId, transfer(accA, accB, "4000"));

        assertThat(balanceOf(accA)).isEqualByComparingTo("6000");
        assertThat(balanceOf(accB)).isEqualByComparingTo("4000");
    }

    @Test
    @DisplayName("홀드를 풀면 가용잔액이 즉시 돌아온다")
    void release_restoresAvailable() {
        Long holdId = placeHold(accA, new BigDecimal("6000"));
        assertThat(availableOf(accA)).isEqualByComparingTo("4000");

        releaseHold(holdId);

        assertThat(availableOf(accA)).isEqualByComparingTo("10000");
        transferService.withdraw(userId, withdraw(accA, "10000"));
        assertThat(balanceOf(accA)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("가용잔액을 넘는 홀드는 걸 수 없다")
    void place_rejectedBeyondAvailable() {
        assertThatThrownBy(() -> placeHold(accA, new BigDecimal("10001")))
                .isInstanceOf(InsufficientBalanceException.class);

        placeHold(accA, new BigDecimal("6000"));

        assertThatThrownBy(() -> placeHold(accA, new BigDecimal("4001")))
                .isInstanceOf(InsufficientBalanceException.class);
        placeHold(accA, new BigDecimal("4000"));
        assertThat(availableOf(accA)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("이미 끝난 홀드는 다시 소비·해제·만료할 수 없다")
    void closedHold_cannotChangeAgain() {
        Long holdId = placeHold(accA, new BigDecimal("1000"));
        releaseHold(holdId);

        assertThatThrownBy(() -> releaseHold(holdId)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> inLockedTx(holdId, holdService::consume)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> inLockedTx(holdId, holdService::expire)).isInstanceOf(IllegalStateException.class);
        assertThat(holdRepository.findById(holdId).orElseThrow().getStatus()).isEqualTo(AccountHold.Status.RELEASED);
    }

    @Test
    @DisplayName("트랜잭션 밖에서는 홀드를 걸 수 없다 (계좌 락 없이 호출하는 실수를 막는다)")
    void place_requiresTransaction() {
        Account account = accountRepository.findByAccountNumber(accA).orElseThrow();
        Transaction pending = transactionRepository.save(pendingWithdrawal(account, new BigDecimal("1000")));

        assertThatThrownBy(() -> holdService.place(account, pending, new BigDecimal("1000")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(holdRepository.count()).isZero();
    }

    @Test
    @DisplayName("홀드가 있는 계좌에 동시 출금이 몰려도 가용잔액을 넘는 출금은 모두 실패한다")
    void concurrentWithdrawals_neverExceedAvailable() throws InterruptedException {
        placeHold(accA, new BigDecimal("6000")); // 가용잔액 4,000

        int threads = 10;
        AtomicInteger success = new AtomicInteger();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        runConcurrently(threads, i -> {
            try {
                transferService.withdraw(userId, withdraw(accA, "1000"));
                success.incrementAndGet();
            } catch (Throwable t) {
                failures.add(t);
            }
        });

        // 1,000원 × 10건 중 가용잔액 4,000원만큼인 4건만 성공해야 한다
        assertThat(balanceOf(accA)).isEqualByComparingTo("6000");
        assertThat(availableOf(accA)).isEqualByComparingTo("0");
        assertThat(success.get()).isEqualTo(4);
        assertThat(failures).hasSize(6).allMatch(t -> t instanceof InsufficientBalanceException);
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();
    }

    @Test
    @DisplayName("홀드 생성·해제·출금이 동시에 섞여도 가용잔액이 음수가 되는 순간이 없다")
    void concurrentPlaceReleaseWithdraw_availableNeverNegative() throws InterruptedException {
        // 잔액 10,000. 홀드 생성 12건(2,000원), 출금 12건(2,000원)이 경쟁하고, 생성에 성공한 홀드의 절반은 바로 해제한다.
        BigDecimal unit = new BigDecimal("2000");
        int perKind = 12;

        AtomicInteger withdrawn = new AtomicInteger();
        AtomicInteger placed = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        // 관찰자: 원장잔액과 ACTIVE 홀드 합계를 한 문장(한 스냅샷)으로 읽어 최솟값을 기록한다
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<BigDecimal> minAvailable = new AtomicReference<>(new BigDecimal("10000"));
        Thread observer = new Thread(() -> {
            while (running.get()) {
                BigDecimal now = availableOf(accA);
                minAvailable.accumulateAndGet(now, BigDecimal::min);
            }
        });
        observer.start();

        try {
            runConcurrently(perKind * 2, i -> {
                try {
                    if (i % 2 == 0) {
                        transferService.withdraw(userId, withdraw(accA, unit.toPlainString()));
                        withdrawn.incrementAndGet();
                    } else {
                        Long holdId = placeHold(accA, unit);
                        placed.incrementAndGet();
                        if (i % 4 == 1) {
                            releaseHold(holdId);
                            released.incrementAndGet();
                        }
                    }
                } catch (InsufficientBalanceException expected) {
                    // 가용잔액 부족으로 거절되는 것은 정상
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            });
        } finally {
            running.set(false);
            observer.join(5_000);
        }

        assertThat(unexpected).isEmpty();
        assertThat(minAvailable.get()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // 최종 상태가 일어난 일의 합과 정확히 맞아야 한다
        BigDecimal expectedBalance = new BigDecimal("10000").subtract(unit.multiply(BigDecimal.valueOf(withdrawn.get())));
        BigDecimal expectedHeld = unit.multiply(BigDecimal.valueOf(placed.get() - released.get()));
        assertThat(balanceOf(accA)).isEqualByComparingTo(expectedBalance);
        assertThat(holdService.heldAmount(idOf(accA))).isEqualByComparingTo(expectedHeld);
        assertThat(availableOf(accA)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        // 경쟁이 실제로 있었는지: 요청 총액(48,000)이 잔액(10,000)을 넘으므로 일부는 반드시 거절돼야 한다
        assertThat(withdrawn.get() + placed.get()).isLessThan(perKind * 2);
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();
        assertThat(reconciliationService.trialBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---- 헬퍼 ----

    private String open(BigDecimal initialBalance) {
        return accountService.openAccount(userId,
                new AccountOpenRequest(UUID.randomUUID().toString(), initialBalance)).accountNumber();
    }

    /** 계좌 락을 쥔 트랜잭션에서 보류용 거래를 만들고 홀드를 건다. 실제 2단계 처리(가승인)가 하는 일의 축약판. */
    private Long placeHold(String accountNumber, BigDecimal amount) {
        return tx.execute(status -> {
            Account locked = accountRepository.findByAccountNumberWithLock(accountNumber).orElseThrow();
            Transaction pending = transactionRepository.save(pendingWithdrawal(locked, amount));
            return holdService.place(locked, pending, amount).getId();
        });
    }

    private void releaseHold(Long holdId) {
        inLockedTx(holdId, holdService::release);
    }

    /** 홀드가 걸린 계좌의 락을 잡은 뒤 홀드에 동작을 적용한다. */
    private void inLockedTx(Long holdId, java.util.function.Consumer<AccountHold> action) {
        tx.executeWithoutResult(status -> {
            AccountHold hold = holdRepository.findById(holdId).orElseThrow();
            // 계좌 엔티티를 락 없이 먼저 로딩하면 안 된다(getAccountNumber()는 지연 로딩을 일으킨다).
            // 락 쿼리가 더 새로운 버전을 읽으면 Hibernate가 버전 충돌로 예외를 던진다. ID로 번호만 읽는다.
            String accountNumber = accountRepository.findAccountNumberById(hold.getAccount().getId()).orElseThrow();
            accountRepository.findByAccountNumberWithLock(accountNumber).orElseThrow();
            action.accept(hold);
        });
    }

    private Transaction pendingWithdrawal(Account account, BigDecimal amount) {
        return Transaction.builder()
                .fromAccount(account)
                .amount(amount)
                .type(Transaction.TransactionType.WITHDRAWAL)
                .description("홀드 테스트")
                .build();
    }

    private WithdrawRequest withdraw(String accountNumber, String amount) {
        return new WithdrawRequest(accountNumber, new BigDecimal(amount), UUID.randomUUID().toString(), "홀드 테스트");
    }

    private TransferRequest transfer(String from, String to, String amount) {
        return new TransferRequest(UUID.randomUUID().toString(), from, to, new BigDecimal(amount), "홀드 테스트");
    }

    private Long idOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getId();
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }

    /** 원장잔액 − ACTIVE 홀드 합계를 SQL 한 문장으로 읽는다(두 값이 같은 스냅샷에서 나온다). */
    private BigDecimal availableOf(String accountNumber) {
        return jdbcTemplate.queryForObject("""
                SELECT a.balance - COALESCE((SELECT SUM(h.amount) FROM account_holds h
                                             WHERE h.account_id = a.id AND h.status = 'ACTIVE'), 0)
                FROM accounts a WHERE a.account_number = ?
                """, BigDecimal.class, accountNumber);
    }

    private void runConcurrently(int threads, java.util.function.IntConsumer task) throws InterruptedException {
        // 커넥션 풀(20)보다 적게 써서 풀 포화가 아니라 락 경합만 남긴다
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threads, 12));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    task.accept(index);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
    }
}
