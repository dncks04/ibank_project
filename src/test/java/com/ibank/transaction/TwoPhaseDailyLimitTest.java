package com.ibank.transaction;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.entity.Transaction.TransactionStatus;
import com.ibank.domain.transaction.exception.DailyLimitExceededException;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransactionProcessor;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.support.DatabaseCleaner;
import com.ibank.support.ScriptedFraudDecisionPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 1일 출금 한도와 보류 거래의 관계. 한도를 낮춰(단건 10,000 / 1일 20,000) 경계를 확인한다.
 *
 * <p>사용량 = 오늘 <b>완료된</b> 출금(완료 시각 기준) + 지금 <b>묶여 있는</b> 금액.
 * 보류된 금액은 한도를 차지하고, 취소되면 즉시 돌려받으며, 실행되면 실행된 날의 사용량이 된다.
 */
@SpringBootTest(properties = {
        "ibank.limits.per-transaction-max=10000",
        "ibank.limits.daily-outflow-max=20000"
})
@Testcontainers(disabledWithoutDocker = true)
class TwoPhaseDailyLimitTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class ScriptedPortConfig {
        @Bean
        @Primary
        ScriptedFraudDecisionPort scriptedFraudDecisionPort() {
            return new ScriptedFraudDecisionPort();
        }
    }

    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private ScriptedFraudDecisionPort fds;
    @Autowired private TransferService transferService;
    @Autowired private TransactionProcessor processor;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long userId;
    private String accA;
    private String accB;

    @BeforeEach
    void setUp() {
        databaseCleaner.clearAll();
        fds.reset();

        userId = userRepository.save(User.builder()
                .loginId("limit2p-user").password("x").name("한도유저")
                .email("limit2p@test.com").role(User.UserRole.ROLE_USER).build()).getId();
        accA = open(new BigDecimal("100000"));
        accB = open(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("보류된 금액은 한도를 차지한다 (보류를 쌓아 두었다가 한꺼번에 실행해 한도를 넘길 수 없다)")
    void heldAmount_countsTowardLimit() {
        fds.holdAll("VELOCITY");
        transferService.transfer(userId, transfer("10000"));
        transferService.transfer(userId, transfer("10000")); // 묶인 금액 20,000 == 한도

        // 완료된 출금은 0원이지만 한도는 이미 묶인 금액으로 찼다
        assertThat(completedCount()).isZero();
        assertThatThrownBy(() -> transferService.transfer(userId, transfer("1")))
                .isInstanceOf(DailyLimitExceededException.class);
        assertThatThrownBy(() -> transferService.withdraw(userId, withdraw("1")))
                .isInstanceOf(DailyLimitExceededException.class);
        // 거절된 요청은 금액을 묶지 않는다
        assertThat(heldSum()).isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("보류 거래가 취소되면 그 금액은 한도 사용량에서 즉시 빠진다")
    void cancel_freesLimitImmediately() {
        fds.holdAll("VELOCITY");
        TransferResponse first = transferService.transfer(userId, transfer("10000"));
        transferService.transfer(userId, transfer("10000"));
        assertThatThrownBy(() -> transferService.transfer(userId, transfer("1")))
                .isInstanceOf(DailyLimitExceededException.class);

        processor.cancelHeld(first.transactionId());

        // 취소 직후 같은 금액을 바로 다시 쓸 수 있다
        fds.approveAll();
        TransferResponse retry = transferService.transfer(userId, transfer("10000"));
        assertThat(retry.status()).isEqualTo(TransactionStatus.COMPLETED);
        // 다시 한도가 찼다 (완료 10,000 + 묶임 10,000)
        assertThatThrownBy(() -> transferService.transfer(userId, transfer("1")))
                .isInstanceOf(DailyLimitExceededException.class);
    }

    @Test
    @DisplayName("보류 거래를 실행해도 한도를 이중으로 차지하지 않는다 (묶인 금액이 완료 금액으로 바뀔 뿐이다)")
    void execute_doesNotDoubleCount() {
        fds.holdAll("VELOCITY");
        TransferResponse first = transferService.transfer(userId, transfer("10000"));
        TransferResponse second = transferService.transfer(userId, transfer("10000"));

        // 한도가 정확히 찬 상태에서도 자기 금액은 이미 사용량에 들어 있으므로 실행이 된다
        assertThat(processor.executeHeld(first.transactionId()).status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(processor.executeHeld(second.transactionId()).status()).isEqualTo(TransactionStatus.COMPLETED);

        assertThat(balanceOf(accB)).isEqualByComparingTo("20000");
        fds.approveAll();
        assertThatThrownBy(() -> transferService.transfer(userId, transfer("1")))
                .isInstanceOf(DailyLimitExceededException.class);
    }

    @Test
    @DisplayName("어제 보류되어 오늘 실행된 거래는 오늘 한도에 잡힌다 (요청 시각이 아니라 완료 시각 기준)")
    void heldYesterdayExecutedToday_countsToday() {
        fds.holdAll("VELOCITY");
        TransferResponse held = transferService.transfer(userId, transfer("10000"));
        // 요청 시각을 어제로 돌린다
        jdbcTemplate.update("UPDATE transactions SET created_at = created_at - INTERVAL '1 day' WHERE id = ?",
                held.transactionId());

        processor.executeHeld(held.transactionId());

        // 요청 시각으로 셌다면 오늘 사용량이 0이라 20,000을 더 보낼 수 있었을 것이다. 완료 시각으로 세므로 10,000만 남는다.
        fds.approveAll();
        transferService.transfer(userId, transfer("10000"));
        assertThatThrownBy(() -> transferService.transfer(userId, transfer("1")))
                .isInstanceOf(DailyLimitExceededException.class);
    }

    @Test
    @DisplayName("어제 완료된 거래는 오늘 한도를 차지하지 않는다")
    void completedYesterday_doesNotCountToday() {
        fds.approveAll();
        transferService.transfer(userId, transfer("10000"));
        transferService.transfer(userId, transfer("10000"));
        assertThatThrownBy(() -> transferService.transfer(userId, transfer("1")))
                .isInstanceOf(DailyLimitExceededException.class);

        // 완료 시각을 어제로 돌린다 (요청 시각은 오늘 그대로 둔다 — 완료 시각만 보는지 확인)
        jdbcTemplate.update("UPDATE transactions SET completed_at = completed_at - INTERVAL '1 day'");

        transferService.transfer(userId, transfer("10000"));
        assertThat(balanceOf(accB)).isEqualByComparingTo("30000");
    }

    @Test
    @DisplayName("보류 거래를 실행하는 날 한도가 이미 찼으면 실행이 거절되고 거래는 HELD로 남는다")
    void executeHeld_rejectedWhenTodayLimitFull() {
        fds.holdAll("VELOCITY");
        TransferResponse held = transferService.transfer(userId, transfer("10000"));
        fds.approveAll();
        transferService.transfer(userId, transfer("10000")); // 완료 10,000 + 묶임 10,000 = 한도

        // 오늘 완료액만으로 한도(20,000)가 차는 상황을 만든다. 정상 경로로는 보류분이 한도를 미리 차지해
        // 이 상태에 도달할 수 없으므로(그게 이 설계의 목적이다) 완료 거래의 금액을 직접 고친다.
        // 운영 중 한도 설정을 낮춘 경우에 해당한다.
        jdbcTemplate.update("UPDATE transactions SET amount = 20000 WHERE status = 'COMPLETED'");

        assertThatThrownBy(() -> processor.executeHeld(held.transactionId()))
                .isInstanceOf(DailyLimitExceededException.class);
        assertThat(transactionRepository.findById(held.transactionId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.HELD);
        assertThat(heldSum()).isEqualByComparingTo("10000");
    }

    // ---- 헬퍼 ----

    private String open(BigDecimal initialBalance) {
        return accountService.openAccount(userId,
                new AccountOpenRequest(UUID.randomUUID().toString(), initialBalance)).accountNumber();
    }

    private TransferRequest transfer(String amount) {
        return new TransferRequest(UUID.randomUUID().toString(), accA, accB, new BigDecimal(amount), "한도 테스트");
    }

    private WithdrawRequest withdraw(String amount) {
        return new WithdrawRequest(accA, new BigDecimal(amount), UUID.randomUUID().toString(), "한도 테스트");
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }

    private long completedCount() {
        return transactionRepository.findAll().stream()
                .filter(t -> t.getStatus() == TransactionStatus.COMPLETED).count();
    }

    private BigDecimal heldSum() {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM account_holds WHERE status = 'ACTIVE'", BigDecimal.class);
    }
}
