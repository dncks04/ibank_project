package com.ibank.bulkload;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.ledger.service.ReconciliationService;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.dto.LoginRequest;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.domain.user.service.UserService;
import com.ibank.global.time.BusinessClock;
import com.ibank.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 합성 데이터 대량 적재. 과거 시각의 고객·계좌·거래가 원장과 함께 들어가 정산을 통과하는지,
 * 문제 있는 행만 건너뛰고 기록하는지, 다시 돌려도 두 번 들어가지 않는지 확인한다.
 */
@SpringBootTest(properties = {
        "ibank.bulk-load.auto-run=false",       // 테스트가 직접 실행한다
        "ibank.bulk-load.exit-on-finish=false",
        "ibank.bulk-load.chunk-size=3",         // 작은 청크로 여러 번 커밋되게 한다
        "ibank.bulk-load.default-password=synthetic-pass-1",
        // 적재가 한도와 무관함을 보이려고 평소 한도를 낮춘다
        "ibank.limits.per-transaction-max=10000"
})
@ActiveProfiles("bulk-load")
@Testcontainers(disabledWithoutDocker = true)
class BulkLoadTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private BulkLoadService bulkLoadService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private ReconciliationService reconciliationService;
    @Autowired private UserService userService;
    @Autowired private TransferService transferService;
    @Autowired private BusinessClock businessClock;
    @Autowired private JdbcTemplate jdbcTemplate;

    @TempDir Path tempDir;

    private static final String ACC_ELDER = "20190603000001";
    private static final String ACC_SON = "20200110000007";

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        jdbcTemplate.update("DELETE FROM bulk_load_errors");
    }

    @Test
    @DisplayName("고객·계좌·거래가 과거 시각으로 적재되고, 원장이 정상 생성되어 정산을 통과한다")
    void load_createsLedgerAndPassesReconciliation() throws Exception {
        Path dir = writeDataset("ok", validCustomers(), validAccounts(), validTransactions());

        BulkLoadService.Summary summary = bulkLoadService.load(dir);

        assertThat(summary.loadStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(summary.reconciliationStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(summary.consistent()).isTrue();
        assertThat(summary.steps().get("customers")).isEqualTo(new BulkLoadStats.StepCounts(2, 0, 0));
        assertThat(summary.steps().get("accounts")).isEqualTo(new BulkLoadStats.StepCounts(2, 0, 0));
        assertThat(summary.steps().get("transactions")).isEqualTo(new BulkLoadStats.StepCounts(5, 0, 0));

        // 개설 1,000,000 + 연금 3,000,000 + 입금 30,000,000 − 이체 500,000 − 출금 200,000 − 고액 이체 20,000,000
        assertThat(balanceOf(ACC_ELDER)).isEqualByComparingTo("13300000");
        assertThat(balanceOf(ACC_SON)).isEqualByComparingTo("20500000");

        // 과거 시각이 그대로 들어갔다: 거래의 요청·완료 시각과 분개 시각이 모두 입력의 시각
        Transaction pension = transactionRepository.findByIdempotencyKey("bulk-T1").orElseThrow();
        OffsetDateTime expected = OffsetDateTime.parse("2019-06-25T09:00:00+09:00");
        assertThat(businessClock.toBusinessOffset(pension.getCreatedAt())).isEqualTo(expected);
        assertThat(businessClock.toBusinessOffset(pension.getCompletedAt())).isEqualTo(expected);
        assertThat(pension.getStatus()).isEqualTo(Transaction.TransactionStatus.COMPLETED);
        assertThat(pension.getChannel()).isEqualTo(Transaction.Channel.MOBILE);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT created_at) FROM ledger_entries WHERE transaction_id = ?", Long.class, pension.getId()))
                .isEqualTo(1);
        assertThat(businessClock.toBusinessOffset(jdbcTemplate.queryForObject(
                "SELECT MIN(created_at) FROM ledger_entries WHERE transaction_id = ?",
                java.sql.Timestamp.class, pension.getId()).toLocalDateTime())).isEqualTo(expected);
        Transaction teller = transactionRepository.findByIdempotencyKey("bulk-T3").orElseThrow();
        assertThat(teller.getChannel()).isEqualTo(Transaction.Channel.TELLER);

        // 고객과 계좌의 시각, 개인정보 암호화
        User elder = userRepository.findByLoginId("elder0001").orElseThrow();
        assertThat(businessClock.toBusinessOffset(elder.getCreatedAt()))
                .isEqualTo(OffsetDateTime.parse("2019-06-03T10:15:00+09:00"));
        assertThat(elder.getBirthDate()).isEqualTo(LocalDate.of(1951, 4, 12));
        assertThat(jdbcTemplate.queryForObject("SELECT birth_date FROM users WHERE login_id = 'elder0001'", String.class))
                .doesNotContain("1951");
        Account account = accountRepository.findByAccountNumber(ACC_ELDER).orElseThrow();
        assertThat(businessClock.toBusinessOffset(account.getCreatedAt()))
                .isEqualTo(OffsetDateTime.parse("2019-06-03T10:20:00+09:00"));
        // 개설 분개(고객 leg + 클리어링 leg)도 개설 시각으로 들어갔다
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ledger_entries
                WHERE transaction_id IS NULL AND created_at = (SELECT created_at FROM accounts WHERE account_number = ?)
                """, Long.class, ACC_ELDER)).isEqualTo(2);

        // 원장: 모든 분개의 합 0, 계좌별 잔액 == 원장 합계, 시산표 0
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();
        assertThat(reconciliationService.findUnbalancedJournals()).isEmpty();
        assertThat(reconciliationService.trialBalance()).isEqualByComparingTo(BigDecimal.ZERO);

        // 판정·홀드·이벤트를 거치지 않았다
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account_holds", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE decision IS NOT NULL", Long.class)).isZero();
        // 평소 단건 한도(이 테스트에서는 10,000원)를 훨씬 넘는 거래도 적재됐다
        assertThat(transactionRepository.findByIdempotencyKey("bulk-T5").orElseThrow().getAmount())
                .isEqualByComparingTo("20000000");
    }

    @Test
    @DisplayName("적재된 고객은 기본 비밀번호로 로그인해 평소처럼 거래할 수 있다 (평소 경로에서는 한도가 다시 적용된다)")
    void loadedCustomer_canTransactNormally() throws Exception {
        bulkLoadService.load(writeDataset("usable", validCustomers(), validAccounts(), validTransactions()));

        assertThat(userService.login(new LoginRequest("elder0001", "synthetic-pass-1")).accessToken()).isNotBlank();
        // password가 지정된 고객은 그 비밀번호로
        assertThat(userService.login(new LoginRequest("son00001", "son-own-pass-9")).accessToken()).isNotBlank();

        Long elderId = userRepository.findByLoginId("elder0001").orElseThrow().getId();
        TransferResponse response = transferService.transfer(elderId, new TransferRequest(
                UUID.randomUUID().toString(), ACC_ELDER, ACC_SON, new BigDecimal("5000"), "적재 후 이체"));
        assertThat(response.status()).isEqualTo(Transaction.TransactionStatus.COMPLETED);
        assertThat(balanceOf(ACC_ELDER)).isEqualByComparingTo("13295000");
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();
    }

    @Test
    @DisplayName("문제 있는 행만 건너뛰고 이유를 기록하며, 나머지는 적재되고 정합성도 유지된다")
    void badRows_areSkippedAndRecorded() throws Exception {
        List<String> customers = new ArrayList<>(validCustomers());
        customers.add("{\"loginId\":\"broken json");                                                    // 3행: JSON 깨짐
        customers.add("{\"loginId\":\"dup00001\",\"name\":\"중복\",\"email\":\"ELDER0001@synthetic.test\","
                + "\"birthDate\":\"1960-01-01\",\"joinedAt\":\"2020-01-01T00:00:00+09:00\"}");          // 4행: 이메일 중복(대소문자만 다름)
        customers.add("{\"loginId\":\"nobirth1\",\"name\":\"생일없음\",\"email\":\"nb@synthetic.test\","
                + "\"joinedAt\":\"2020-01-01T00:00:00+09:00\"}");                                       // 5행: 필수 값 누락

        List<String> accounts = new ArrayList<>(validAccounts());
        accounts.add("{\"accountNumber\":\"20210101000099\",\"customerLoginId\":\"ghost\","
                + "\"initialBalance\":\"0\",\"openedAt\":\"2021-01-01T00:00:00+09:00\"}");               // 3행: 없는 고객

        List<String> transactions = new ArrayList<>(validTransactions());
        transactions.add("{\"txRef\":\"B1\",\"type\":\"WITHDRAWAL\",\"fromAccountNumber\":\"" + ACC_SON
                + "\",\"amount\":\"999999999\",\"occurredAt\":\"2021-01-01T00:00:00+09:00\"}");          // 7행: 잔액 부족 (4행은 빈 줄)
        transactions.add("{\"txRef\":\"B2\",\"type\":\"TRANSFER\",\"fromAccountNumber\":\"" + ACC_ELDER
                + "\",\"toAccountNumber\":\"00000000000000\",\"amount\":\"1000\",\"occurredAt\":\"2021-01-01T00:00:00+09:00\"}"); // 8행: 없는 계좌
        transactions.add("{\"txRef\":\"B3\",\"type\":\"DEPOSIT\",\"toAccountNumber\":\"" + ACC_ELDER
                + "\",\"amount\":\"1000.555\",\"occurredAt\":\"2021-01-01T00:00:00+09:00\"}");           // 9행: 소수 셋째 자리
        transactions.add("{\"txRef\":\"B4\",\"type\":\"DEPOSIT\",\"toAccountNumber\":\"" + ACC_ELDER
                + "\",\"amount\":\"1000\",\"occurredAt\":\"2999-01-01T00:00:00+09:00\"}");               // 10행: 미래 시각
        transactions.add("{\"txRef\":\"G1\",\"type\":\"DEPOSIT\",\"toAccountNumber\":\"" + ACC_SON
                + "\",\"amount\":\"777\",\"occurredAt\":\"2021-02-01T00:00:00+09:00\"}");                // 11행: 정상 (문제 행 뒤에도 계속 적재된다)

        BulkLoadService.Summary summary = bulkLoadService.load(writeDataset("bad", customers, accounts, transactions));

        assertThat(summary.loadStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(summary.steps().get("customers")).isEqualTo(new BulkLoadStats.StepCounts(2, 0, 3));
        assertThat(summary.steps().get("accounts")).isEqualTo(new BulkLoadStats.StepCounts(2, 0, 1));
        assertThat(summary.steps().get("transactions")).isEqualTo(new BulkLoadStats.StepCounts(6, 0, 4));
        assertThat(summary.totalErrors()).isEqualTo(8);
        assertThat(summary.consistent()).as("건너뛴 행이 있어도 들어간 데이터의 정합성은 유지된다").isTrue();

        // 건너뛴 행마다 줄 번호와 이유가 남는다
        List<String> errors = jdbcTemplate.query(
                "SELECT step || ':' || line_number || ':' || reason FROM bulk_load_errors "
                        + "WHERE job_execution_id = ? ORDER BY step, line_number",
                (rs, n) -> rs.getString(1), summary.loadJobExecutionId());
        assertThat(errors).hasSize(8);
        assertThat(errors).anySatisfy(e -> assertThat(e).startsWith("customers:3:").contains("JSON"));
        assertThat(errors).anySatisfy(e -> assertThat(e).startsWith("customers:4:").contains("이메일"));
        assertThat(errors).anySatisfy(e -> assertThat(e).startsWith("customers:5:").contains("birthDate"));
        assertThat(errors).anySatisfy(e -> assertThat(e).startsWith("accounts:3:").contains("고객이 없습니다"));
        assertThat(errors).anySatisfy(e -> assertThat(e).startsWith("transactions:7:").contains("잔액 부족"));
        assertThat(errors).anySatisfy(e -> assertThat(e).startsWith("transactions:8:").contains("계좌가 없습니다"));
        assertThat(errors).anySatisfy(e -> assertThat(e).startsWith("transactions:9:").contains("소수"));
        assertThat(errors).anySatisfy(e -> assertThat(e).startsWith("transactions:10:").contains("미래"));

        // 건너뛴 행은 흔적 없이 빠졌고, 그 뒤의 정상 행은 들어갔다
        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(balanceOf(ACC_SON)).isEqualByComparingTo("20500777");
        assertThat(balanceOf(ACC_ELDER)).isEqualByComparingTo("13300000");
        assertThat(reconciliationService.trialBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("같은 데이터를 (새 디렉터리로) 다시 적재해도 두 번 들어가지 않고, 추가된 행만 적재된다")
    void reload_doesNotDuplicate() throws Exception {
        bulkLoadService.load(writeDataset("first", validCustomers(), validAccounts(), validTransactions()));
        BigDecimal elderBefore = balanceOf(ACC_ELDER);
        long ledgerBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class);

        List<String> more = new ArrayList<>(validTransactions());
        more.add("{\"txRef\":\"T6\",\"type\":\"DEPOSIT\",\"toAccountNumber\":\"" + ACC_ELDER
                + "\",\"amount\":\"12345\",\"occurredAt\":\"2021-03-01T00:00:00+09:00\"}");
        BulkLoadService.Summary second = bulkLoadService.load(
                writeDataset("second", validCustomers(), validAccounts(), more));

        assertThat(second.steps().get("customers")).isEqualTo(new BulkLoadStats.StepCounts(0, 2, 0));
        assertThat(second.steps().get("accounts")).isEqualTo(new BulkLoadStats.StepCounts(0, 2, 0));
        assertThat(second.steps().get("transactions")).isEqualTo(new BulkLoadStats.StepCounts(1, 5, 0));
        assertThat(balanceOf(ACC_ELDER)).isEqualByComparingTo(elderBefore.add(new BigDecimal("12345")));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class))
                .isEqualTo(ledgerBefore + 2); // 추가된 입금의 분개 한 번(leg 2건)만 늘었다
        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(accountRepository.count()).isEqualTo(2);
        assertThat(second.consistent()).isTrue();
    }

    // ---- 데이터 ----

    private List<String> validCustomers() {
        return List.of(
                "{\"loginId\":\"elder0001\",\"name\":\"김영희\",\"email\":\"elder0001@synthetic.test\","
                        + "\"birthDate\":\"1951-04-12\",\"joinedAt\":\"2019-06-03T10:15:00+09:00\"}",
                "{\"loginId\":\"son00001\",\"name\":\"김아들\",\"email\":\"son00001@synthetic.test\","
                        + "\"birthDate\":\"1980-09-30\",\"joinedAt\":\"2020-01-10T09:00:00+09:00\","
                        + "\"password\":\"son-own-pass-9\"}");
    }

    private List<String> validAccounts() {
        return List.of(
                "{\"accountNumber\":\"" + ACC_ELDER + "\",\"customerLoginId\":\"elder0001\","
                        + "\"initialBalance\":\"1000000\",\"openedAt\":\"2019-06-03T10:20:00+09:00\"}",
                "{\"accountNumber\":\"" + ACC_SON + "\",\"customerLoginId\":\"son00001\","
                        + "\"initialBalance\":\"0\",\"openedAt\":\"2020-01-10T09:05:00+09:00\"}");
    }

    private List<String> validTransactions() {
        return List.of(
                "{\"txRef\":\"T1\",\"type\":\"DEPOSIT\",\"toAccountNumber\":\"" + ACC_ELDER
                        + "\",\"amount\":\"3000000\",\"occurredAt\":\"2019-06-25T09:00:00+09:00\",\"description\":\"연금\"}",
                "{\"txRef\":\"T2\",\"type\":\"TRANSFER\",\"fromAccountNumber\":\"" + ACC_ELDER
                        + "\",\"toAccountNumber\":\"" + ACC_SON + "\",\"amount\":\"500000\",\"occurredAt\":\"2020-02-01T14:30:00+09:00\"}",
                "{\"txRef\":\"T3\",\"type\":\"WITHDRAWAL\",\"fromAccountNumber\":\"" + ACC_ELDER
                        + "\",\"amount\":200000,\"occurredAt\":\"2020-02-03T11:00:00+09:00\",\"channel\":\"TELLER\"}",
                "",  // 빈 줄은 무시된다
                "{\"txRef\":\"T4\",\"type\":\"DEPOSIT\",\"toAccountNumber\":\"" + ACC_ELDER
                        + "\",\"amount\":\"30000000\",\"occurredAt\":\"2020-03-01T10:00:00+09:00\",\"description\":\"예금 만기\"}",
                "{\"txRef\":\"T5\",\"type\":\"TRANSFER\",\"fromAccountNumber\":\"" + ACC_ELDER
                        + "\",\"toAccountNumber\":\"" + ACC_SON + "\",\"amount\":\"20000000\",\"occurredAt\":\"2020-03-01T10:20:00+09:00\"}");
    }

    private Path writeDataset(String name, List<String> customers, List<String> accounts, List<String> transactions)
            throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve(name));
        Files.write(dir.resolve("customers.jsonl"), customers, StandardCharsets.UTF_8);
        Files.write(dir.resolve("accounts.jsonl"), accounts, StandardCharsets.UTF_8);
        Files.write(dir.resolve("transactions.jsonl"), transactions, StandardCharsets.UTF_8);
        return dir;
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }
}
