package com.ibank.batch;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.dto.AccountResponse;
import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.interest.entity.InterestAccrual;
import com.ibank.domain.interest.repository.InterestAccrualRepository;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.ledger.service.ReconciliationService;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이자 적립·지급 배치 검증.
 *
 * - 적립: 일할 이자가 정확히 계산되고, 같은 날 재실행해도 중복 적립되지 않는다(멱등).
 * - 지급: 미지급 적립분이 계좌에 입금되고 원장 분개(고객 CREDIT + INTEREST_EXPENSE DEBIT)가 남으며,
 *   재실행해도 두 번 지급되지 않고, 정산 불변식(잔액==원장합, 시산표==0)이 보존된다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class InterestBatchTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JobLauncher jobLauncher;
    @Autowired @Qualifier("interestAccrualJob") Job interestAccrualJob;
    @Autowired @Qualifier("interestPaymentJob") Job interestPaymentJob;
    @Autowired AccountService accountService;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired InterestAccrualRepository interestAccrualRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired ReconciliationService reconciliationService;

    private Account account;

    @BeforeEach
    void setUp() {
        interestAccrualRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(User.builder()
                .loginId("interest-user").password("x").name("이자유저")
                .email("interest@test.com").role(User.UserRole.ROLE_USER).build());
        // 서비스로 개설 → 초기 잔액이 원장에 기록됨(balance == ledgerSum 으로 시작).
        // 잔액 3,650,000원, 연 2%, 365일 → 일할 이자 = 3,650,000 * 0.02 / 365 = 200.00원
        AccountResponse opened = accountService.openAccount(user.getId(),
                new AccountOpenRequest(java.util.UUID.randomUUID().toString(), new BigDecimal("3650000")));
        account = accountRepository.findById(opened.id()).orElseThrow();
    }

    private JobExecution runAccrual(LocalDate date) throws Exception {
        return jobLauncher.run(interestAccrualJob, new JobParametersBuilder()
                .addString("accrualDate", date.toString())
                .addLong("runAt", System.nanoTime())
                .toJobParameters());
    }

    @Test
    @DisplayName("일일 적립 - 일할 이자를 정확히 계산하고 같은 날 재실행해도 중복 적립하지 않는다")
    void accrual_isPreciseAndIdempotent() throws Exception {
        LocalDate date = LocalDate.of(2026, 6, 1);

        assertThat(runAccrual(date).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(interestAccrualRepository.count()).isEqualTo(1);
        InterestAccrual accrual = interestAccrualRepository.findByAccountIdAndPaidFalse(account.getId()).get(0);
        assertThat(accrual.getInterestAmount()).isEqualByComparingTo(new BigDecimal("200.00"));

        // 같은 날 다시 실행 → 중복 적립 없음
        assertThat(runAccrual(date).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(interestAccrualRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("월말 지급 - 미지급 적립분을 입금하고 원장 분개를 남기며 정산 불변식을 보존한다")
    void payment_depositsAndKeepsLedgerBalanced() throws Exception {
        // 3일치 적립: 200 * 3 = 600원
        runAccrual(LocalDate.of(2026, 6, 1));
        runAccrual(LocalDate.of(2026, 6, 2));
        runAccrual(LocalDate.of(2026, 6, 3));

        JobExecution payment = jobLauncher.run(interestPaymentJob, new JobParametersBuilder()
                .addLong("runAt", System.nanoTime())
                .toJobParameters());
        assertThat(payment.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 잔액에 이자 600원 반영
        Account after = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(after.getBalance()).isEqualByComparingTo(new BigDecimal("3650600"));

        // 적립분은 모두 지급 처리
        assertThat(interestAccrualRepository.findByAccountIdAndPaidFalse(account.getId())).isEmpty();

        // 정산 불변식: 계좌 잔액 == 원장 합계, 시스템 시산표 == 0
        assertThat(accountRepository.findAccountLedgerBalance(account.getId()).orElseThrow().getLedgerSum())
                .isEqualByComparingTo(new BigDecimal("3650600"));
        assertThat(reconciliationService.findUnbalancedJournals()).isEmpty();
        assertThat(reconciliationService.trialBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("월말 지급 - 재실행해도 이미 지급된 적립분을 두 번 지급하지 않는다")
    void payment_isIdempotent() throws Exception {
        runAccrual(LocalDate.of(2026, 6, 1));

        jobLauncher.run(interestPaymentJob, new JobParametersBuilder()
                .addLong("runAt", System.nanoTime()).toJobParameters());
        BigDecimal afterFirst = accountRepository.findById(account.getId()).orElseThrow().getBalance();

        // 재실행: 미지급분이 없으므로 잔액 변화 없음
        jobLauncher.run(interestPaymentJob, new JobParametersBuilder()
                .addLong("runAt", System.nanoTime()).toJobParameters());
        BigDecimal afterSecond = accountRepository.findById(account.getId()).orElseThrow().getBalance();

        assertThat(afterSecond).isEqualByComparingTo(afterFirst);
    }
}
