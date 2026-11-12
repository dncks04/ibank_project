package com.ibank.outbox;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.outbox.EventType;
import com.ibank.domain.outbox.OutboxRecorder;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.internal.event.InternalEventService;
import com.ibank.internal.event.InternalEventsResponse;
import com.ibank.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox 이벤트의 전달 순서 보장과 그 한계.
 *
 * <p>소비자는 "id N까지 받았다"만 기억한다. 그래서 한 번 N까지 내려간 뒤에 N 이하의 이벤트가 새로 나타나면
 * 그 이벤트는 영영 전달되지 않는다. 늦게 커밋되는 트랜잭션이 있어도 그런 일이 없는지, 그리고 그 대가로
 * 긴 쓰기 트랜잭션이 전달을 지연시키는 한계가 문서에 적은 대로인지 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class OutboxOrderingTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private OutboxRecorder recorder;
    @Autowired private InternalEventService eventService;
    @Autowired private TransferService transferService;
    @Autowired private AccountService accountService;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;

    private TransactionTemplate tx;
    /** 이 테스트가 시작할 때의 공개 위치. 공개 번호는 테스트 사이에 되돌아가지 않으므로 여기서부터 읽는다. */
    private long cursorAtStart;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        tx = new TransactionTemplate(transactionManager);
        cursorAtStart = jdbcTemplate.queryForObject("SELECT last_id FROM outbox_publish_state", Long.class);
    }

    @Test
    @DisplayName("먼저 시작해 늦게 커밋되는 트랜잭션이 있으면, 그 뒤의 이벤트를 먼저 내보내지 않는다 (건너뛰기 방지)")
    void lateCommitter_isNotSkipped() throws Exception {
        CountDownLatch slowInserted = new CountDownLatch(1);
        CountDownLatch letSlowCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        // 트랜잭션 A: 이벤트를 기록하고 커밋하지 않은 채 멈춘다
        var slow = pool.submit(() -> tx.executeWithoutResult(status -> {
            recorder.record(EventType.DEPOSIT, "slow-A", Map.of("who", "A"));
            slowInserted.countDown();
            await(letSlowCommit);
        }));
        assertThat(slowInserted.await(10, TimeUnit.SECONDS)).isTrue();

        // 트랜잭션 B: A보다 늦게 시작했지만 먼저 커밋한다
        tx.executeWithoutResult(status -> recorder.record(EventType.DEPOSIT, "fast-B", Map.of("who", "B")));

        // B는 커밋됐지만 아직 내보내면 안 된다. 지금 B를 내보내면 소비자의 커서가 B를 지나가고,
        // 나중에 커밋되는 A(더 앞선 트랜잭션)는 커서 뒤에 남아 전달되지 않는다.
        InternalEventsResponse whileSlowOpen = eventService.read(cursorAtStart, 100);
        assertThat(whileSlowOpen.events()).isEmpty();
        assertThat(whileSlowOpen.hasMore()).isFalse();
        assertThat(whileSlowOpen.nextAfter()).isEqualTo(cursorAtStart);

        // A가 커밋되면 둘 다 나가고, 순서는 트랜잭션이 시작한 순서(A → B)다
        letSlowCommit.countDown();
        slow.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        InternalEventsResponse afterCommit = eventService.read(cursorAtStart, 100);
        assertThat(afterCommit.events()).extracting(InternalEventsResponse.Event::aggregateId)
                .containsExactly("slow-A", "fast-B");
        assertThat(afterCommit.events().get(0).id()).isEqualTo(cursorAtStart + 1);
        assertThat(afterCommit.events().get(1).id()).isEqualTo(cursorAtStart + 2);
    }

    @Test
    @DisplayName("롤백된 트랜잭션의 이벤트는 전달되지 않고, 번호에 구멍도 남기지 않는다")
    void rolledBackEvent_leavesNoGap() {
        tx.executeWithoutResult(status -> recorder.record(EventType.DEPOSIT, "first", Map.of()));
        tx.executeWithoutResult(status -> {
            recorder.record(EventType.DEPOSIT, "rolled-back", Map.of());
            status.setRollbackOnly();
        });
        tx.executeWithoutResult(status -> recorder.record(EventType.DEPOSIT, "second", Map.of()));

        List<InternalEventsResponse.Event> events = eventService.read(cursorAtStart, 100).events();

        assertThat(events).extracting(InternalEventsResponse.Event::aggregateId).containsExactly("first", "second");
        assertThat(events).extracting(InternalEventsResponse.Event::id)
                .containsExactly(cursorAtStart + 1, cursorAtStart + 2);
    }

    @Test
    @DisplayName("[한계] Outbox와 무관한 쓰기 트랜잭션이 오래 열려 있으면, 그동안 기록된 이벤트는 전달이 멈춘다")
    void longWritingTransaction_delaysDelivery() throws Exception {
        Long userId = saveUser();
        String from = open(userId, new BigDecimal("100000"));
        String to = open(userId, BigDecimal.ZERO);
        long cursor = drain(cursorAtStart); // 계좌 개설 이벤트까지 소비

        try (Connection longTx = dataSource.getConnection()) {
            longTx.setAutoCommit(false);
            try (Statement st = longTx.createStatement()) {
                // 트랜잭션 ID를 받는다 = 쓰기 트랜잭션이 된다. 실제로는 아무 테이블이나 한 행만 수정해도 같다.
                st.execute("SELECT pg_current_xact_id()");
            }

            // 긴 트랜잭션이 열려 있는 동안 이체가 완료된다 (이체 자체는 막히지 않는다)
            transferService.transfer(userId, transfer(from, to, "1000"));
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE id IS NULL", Long.class))
                    .as("이벤트는 기록됐다").isEqualTo(2);

            // 그러나 전달되지 않는다
            InternalEventsResponse blocked = eventService.read(cursor, 100);
            assertThat(blocked.events()).isEmpty();
            assertThat(blocked.hasMore()).isFalse();

            longTx.commit();
        }

        // 긴 트랜잭션이 끝나면 밀려 있던 이벤트가 전달된다
        assertThat(eventService.read(cursor, 100).events())
                .extracting(InternalEventsResponse.Event::eventType)
                .containsExactly("TRANSACTION_REQUESTED", "TRANSACTION_COMPLETED");
    }

    @Test
    @DisplayName("읽기만 하는 트랜잭션은 오래 열려 있어도 전달을 막지 않는다")
    void longReadOnlyTransaction_doesNotDelay() throws Exception {
        Long userId = saveUser();
        String from = open(userId, new BigDecimal("100000"));
        String to = open(userId, BigDecimal.ZERO);
        long cursor = drain(cursorAtStart);

        try (Connection longTx = dataSource.getConnection()) {
            longTx.setAutoCommit(false);
            longTx.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (Statement st = longTx.createStatement()) {
                st.executeQuery("SELECT COUNT(*) FROM accounts").close(); // 스냅샷을 잡지만 트랜잭션 ID는 받지 않는다
            }

            transferService.transfer(userId, transfer(from, to, "1000"));

            assertThat(eventService.read(cursor, 100).events())
                    .extracting(InternalEventsResponse.Event::eventType)
                    .containsExactly("TRANSACTION_REQUESTED", "TRANSACTION_COMPLETED");
            longTx.rollback();
        }
    }

    @Test
    @DisplayName("이체가 동시에 몰리는 동안 계속 가져가도 빠지거나 겹치는 이벤트가 없고 번호는 끊김 없이 증가한다")
    void concurrentProducersAndConsumer_noLossNoDuplicate() throws Exception {
        Long userId = saveUser();
        int accounts = 8;
        int transfersPerThread = 15;
        List<String> froms = new ArrayList<>();
        for (int i = 0; i < accounts; i++) {
            froms.add(open(userId, new BigDecimal("1000000")));
        }
        String to = open(userId, BigDecimal.ZERO);
        long cursor = drain(cursorAtStart);
        long firstExpectedId = cursor + 1;

        // 소비자: 생산이 진행되는 동안 작은 단위로 계속 가져간다
        List<InternalEventsResponse.Event> consumed = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean producing = new AtomicBoolean(true);
        long[] consumerCursor = {cursor};
        Thread consumer = new Thread(() -> {
            while (producing.get()) {
                InternalEventsResponse page = eventService.read(consumerCursor[0], 7);
                consumed.addAll(page.events());
                consumerCursor[0] = page.nextAfter();
            }
        });
        consumer.start();

        // 생산자: 계좌마다 한 스레드가 이체를 이어서 보낸다 (서로 다른 계좌라 락으로 직렬화되지 않고 실제로 겹친다)
        ExecutorService pool = Executors.newFixedThreadPool(accounts);
        CountDownLatch done = new CountDownLatch(accounts);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (String from : froms) {
            pool.submit(() -> {
                try {
                    for (int n = 0; n < transfersPerThread; n++) {
                        transferService.transfer(userId, transfer(from, to, "100"));
                    }
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();
        producing.set(false);
        consumer.join(10_000);
        assertThat(failures).isEmpty();

        // 남은 것을 마저 가져간다
        InternalEventsResponse page;
        do {
            page = eventService.read(consumerCursor[0], 7);
            consumed.addAll(page.events());
            consumerCursor[0] = page.nextAfter();
        } while (page.hasMore() || !page.events().isEmpty());

        int expected = accounts * transfersPerThread * 2; // 이체 한 건당 REQUESTED + COMPLETED
        List<Long> ids = consumed.stream().map(InternalEventsResponse.Event::id).toList();
        assertThat(ids).as("모든 이벤트가 한 번씩").hasSize(expected);
        assertThat(new HashSet<>(ids)).as("중복 없음").hasSize(expected);
        for (int i = 0; i < ids.size(); i++) {
            assertThat(ids.get(i)).as("가져간 순서대로 번호가 1씩 증가 (%d번째)", i).isEqualTo(firstExpectedId + i);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE id IS NULL", Long.class))
                .as("공개되지 않고 남은 이벤트 없음").isZero();

        // 같은 거래의 이벤트는 REQUESTED가 COMPLETED보다 먼저 온다
        Map<String, List<String>> typesByTransaction = new java.util.LinkedHashMap<>();
        consumed.forEach(e -> typesByTransaction.computeIfAbsent(e.aggregateId(), k -> new ArrayList<>()).add(e.eventType()));
        assertThat(typesByTransaction).hasSize(accounts * transfersPerThread);
        assertThat(typesByTransaction.values())
                .allSatisfy(types -> assertThat(types).containsExactly("TRANSACTION_REQUESTED", "TRANSACTION_COMPLETED"));
    }

    // ---- 헬퍼 ----

    /** 지금 공개 가능한 이벤트를 모두 소비하고 마지막 위치를 돌려준다. */
    private long drain(long after) {
        long cursor = after;
        InternalEventsResponse page;
        do {
            page = eventService.read(cursor, 500);
            cursor = page.nextAfter();
        } while (page.hasMore());
        return cursor;
    }

    private Long saveUser() {
        return userRepository.save(User.builder()
                .loginId("outbox-order").password("x").name("이벤트").email("outbox-order@test.com")
                .birthDate(LocalDate.of(1990, 1, 1)).role(User.UserRole.ROLE_USER).build()).getId();
    }

    private String open(Long userId, BigDecimal balance) {
        return accountService.openAccount(userId,
                new AccountOpenRequest(UUID.randomUUID().toString(), balance)).accountNumber();
    }

    private TransferRequest transfer(String from, String to, String amount) {
        return new TransferRequest(UUID.randomUUID().toString(), from, to, new BigDecimal(amount), "이벤트 순서");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
