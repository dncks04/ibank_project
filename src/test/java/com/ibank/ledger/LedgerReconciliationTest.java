package com.ibank.ledger;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.dto.AccountResponse;
import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.ledger.service.ReconciliationService;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원장 정합성 검증. account.balance == SUM(CREDIT) - SUM(DEBIT) 불변식이
 * 개설/동시 이체 후에도 유지되는지 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class LedgerReconciliationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired AccountService accountService;
    @Autowired TransferService transferService;
    @Autowired ReconciliationService reconciliationService;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private String accA;
    private String accB;
    private Long accAId;
    private Long accBId;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(User.builder()
                .loginId("ledger-user").password("x")
                .name("원장유저").email("ledger@test.com")
                .role(User.UserRole.ROLE_USER).build());

        AccountResponse a = accountService.openAccount(user.getId(),
                new AccountOpenRequest(new BigDecimal("100000")));
        AccountResponse b = accountService.openAccount(user.getId(),
                new AccountOpenRequest(BigDecimal.ZERO));
        accA = a.accountNumber();
        accAId = a.id();
        accB = b.accountNumber();
        accBId = b.id();
    }

    @Test
    @DisplayName("계좌 개설 시 초기 잔액이 원장에 기록되고 정합성이 성립한다")
    void opening_recordedAndConsistent() {
        assertThat(reconciliationService.isConsistent(accAId)).isTrue();
        assertThat(reconciliationService.isConsistent(accBId)).isTrue();
        // A: 초기 잔액 100000 → opening 항목 1건, B: 0원 개설 → 항목 없음
        assertThat(ledgerEntryRepository.countByAccountId(accAId)).isEqualTo(1);
        assertThat(ledgerEntryRepository.countByAccountId(accBId)).isEqualTo(0);
    }

    @Test
    @DisplayName("동시 이체 후에도 모든 계좌의 잔액==원장 합계 정합성이 유지된다")
    void reconciliation_holdsAfterConcurrentTransfers() throws InterruptedException {
        int n = 30;
        BigDecimal amount = new BigDecimal("100");

        runConcurrently(n, () -> transferService.transfer(new TransferRequest(
                UUID.randomUUID().toString(), accA, accB, amount, "정합성 테스트")));

        // 전 계좌 정합성 OK (불일치 계좌 없음)
        assertThat(reconciliationService.findInconsistentAccounts()).isEmpty();

        Account a = accountRepository.findByAccountNumber(accA).orElseThrow();
        Account b = accountRepository.findByAccountNumber(accB).orElseThrow();
        assertThat(a.getBalance().add(b.getBalance())).isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(b.getBalance()).isEqualByComparingTo(amount.multiply(BigDecimal.valueOf(n)));

        // 원장 항목 수: A = 1(opening) + n(DEBIT), B = n(CREDIT)
        assertThat(ledgerEntryRepository.countByAccountId(accAId)).isEqualTo(1 + n);
        assertThat(ledgerEntryRepository.countByAccountId(accBId)).isEqualTo(n);
    }

    @Test
    @DisplayName("잔액이 원장과 어긋나면 불일치로 탐지된다 (true positive)")
    void tamperedBalance_isDetectedAsInconsistent() {
        // 원장은 그대로 두고 잔액만 +1 → 불변식 balance == SUM(CREDIT)-SUM(DEBIT) 위반
        jdbcTemplate.update("UPDATE accounts SET balance = balance + 1 WHERE id = ?", accAId);

        assertThat(reconciliationService.isConsistent(accAId)).isFalse();
        assertThat(reconciliationService.findInconsistentAccounts())
                .contains(accAId)
                .doesNotContain(accBId);
    }

    @Test
    @DisplayName("음수 잔액은 DB CHECK 제약이 거부한다 (앱 로직과 무관한 최후 방어선)")
    void negativeBalance_rejectedByDbConstraint() {
        // 도메인 로직을 우회한 직접 UPDATE라도 DB가 막아야 한다
        assertThatThrownBy(() ->
                jdbcTemplate.update("UPDATE accounts SET balance = -1 WHERE id = ?", accAId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void runConcurrently(int n, Runnable task) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(n, 16));
        for (int i = 0; i < n; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    task.run();
                } catch (Exception ignored) {
                    // 실패는 정합성 검증과 무관 (개수 단언에서 드러남)
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
    }
}
