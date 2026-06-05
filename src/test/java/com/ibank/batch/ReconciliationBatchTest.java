package com.ibank.batch;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.dto.AccountResponse;
import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.ledger.entity.ReconciliationResult;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.ledger.repository.ReconciliationResultRepository;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정산 배치 검증. 정합 계좌와 의도적으로 불일치인 계좌를 만든 뒤 잡을 실행하여
 * 전 계좌가 검사되고 불일치가 탐지·기록되는지 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ReconciliationBatchTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JobLauncher jobLauncher;
    @Autowired Job reconciliationJob;
    @Autowired AccountService accountService;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired ReconciliationResultRepository reconciliationResultRepository;

    @BeforeEach
    void setUp() {
        reconciliationResultRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("정산 배치 - 전 계좌를 검사하고 잔액-원장 불일치를 탐지·기록한다")
    void reconciliationJob_detectsInconsistentAccount() throws Exception {
        User user = userRepository.save(User.builder()
                .loginId("recon-user").password("x").name("정산유저")
                .email("recon@test.com").role(User.UserRole.ROLE_USER).build());

        // 정합 계좌: 서비스로 개설 → 초기 잔액이 원장에 기록됨 (balance == ledgerSum)
        AccountResponse good = accountService.openAccount(user.getId(),
                new AccountOpenRequest(new BigDecimal("10000")));

        // 불일치 계좌: 리포지토리로 직접 생성 → 잔액 5000이지만 원장 항목 없음 (balance != ledgerSum)
        Account bad = accountRepository.save(Account.builder()
                .accountNumber("99990101000001").owner(user)
                .initialBalance(new BigDecimal("5000")).build());

        JobExecution execution = jobLauncher.run(reconciliationJob, new JobParametersBuilder()
                .addLong("runAt", System.currentTimeMillis())
                .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 두 계좌 모두 이번 실행에서 검사됨
        assertThat(reconciliationResultRepository.countByJobExecutionId(execution.getId())).isEqualTo(2);
        // 불일치 1건만
        assertThat(reconciliationResultRepository.countByConsistentFalse()).isEqualTo(1);

        ReconciliationResult badResult = reconciliationResultRepository.findAll().stream()
                .filter(r -> r.getAccountId().equals(bad.getId()))
                .findFirst().orElseThrow();
        assertThat(badResult.isConsistent()).isFalse();
        assertThat(badResult.getBalance()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(badResult.getLedgerSum()).isEqualByComparingTo(BigDecimal.ZERO);

        ReconciliationResult goodResult = reconciliationResultRepository.findAll().stream()
                .filter(r -> r.getAccountId().equals(good.id()))
                .findFirst().orElseThrow();
        assertThat(goodResult.isConsistent()).isTrue();
        assertThat(goodResult.getBalance()).isEqualByComparingTo(new BigDecimal("10000"));
    }
}
