package com.ibank.fraud;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.fraud.entity.FraudAlert;
import com.ibank.domain.fraud.repository.FraudAlertRepository;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이상거래 탐지(FDS) 검증. fraud.enabled=true + 낮은 임계치로 띄워, 의심 이체가
 * 보류(HELD)되고 자금이 이동하지 않으며 경보가 남는지 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "ibank.fraud.enabled=true",
        // 신규 수취인 고액 룰만 단독 검증하기 쉽도록 임계치를 낮춘다
        "ibank.fraud.new-payee-high-value-min=1000",
        // velocity·심야 룰은 이 테스트에서 끄듯이 높게 둔다
        "ibank.fraud.velocity-max-outgoing=1000",
        "ibank.fraud.night-high-value-min=999999999"
})
class FraudDetectionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TransferService transferService;
    @Autowired AccountService accountService;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired FraudAlertRepository fraudAlertRepository;

    private Long userId;
    private String fromAcc;
    private String toAcc;

    @BeforeEach
    void setUp() {
        fraudAlertRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(User.builder()
                .loginId("fraud-user").password("x").name("FDS유저")
                .email("fraud@test.com").role(User.UserRole.ROLE_USER).build());
        userId = user.getId();
        fromAcc = accountService.openAccount(userId, new AccountOpenRequest(
                        java.util.UUID.randomUUID().toString(), new BigDecimal("10000000")))
                .accountNumber();
        toAcc = accountService.openAccount(userId, new AccountOpenRequest(
                        java.util.UUID.randomUUID().toString(), BigDecimal.ZERO))
                .accountNumber();
    }

    @Test
    @DisplayName("신규 수취인 고액 이체는 보류되고 자금이 이동하지 않으며 경보가 남는다")
    void newPayeeHighValue_isHeld() {
        BigDecimal balanceBefore = accountRepository.findByAccountNumber(fromAcc).orElseThrow().getBalance();

        TransferResponse response = transferService.transfer(userId, new TransferRequest(
                UUID.randomUUID().toString(), fromAcc, toAcc, new BigDecimal("5000"), "고액 신규 이체"));

        // 거래는 HELD 상태
        assertThat(response.status()).isEqualTo(Transaction.TransactionStatus.HELD);

        // 자금 미이동: 양 계좌 잔액 그대로
        assertThat(accountRepository.findByAccountNumber(fromAcc).orElseThrow().getBalance())
                .isEqualByComparingTo(balanceBefore);
        assertThat(accountRepository.findByAccountNumber(toAcc).orElseThrow().getBalance())
                .isEqualByComparingTo(BigDecimal.ZERO);

        // 경보 1건(OPEN), 신규 수취인 룰 포함
        assertThat(fraudAlertRepository.findByStatusOrderByCreatedAtDesc(FraudAlert.Status.OPEN))
                .singleElement()
                .satisfies(a -> assertThat(a.getTriggeredRules()).contains("NEW_PAYEE_HIGH_VALUE"));
    }

    @Test
    @DisplayName("임계치 미만 이체는 정상 완료된다")
    void belowThreshold_completes() {
        TransferResponse response = transferService.transfer(userId, new TransferRequest(
                UUID.randomUUID().toString(), fromAcc, toAcc, new BigDecimal("500"), "소액 이체"));

        assertThat(response.status()).isEqualTo(Transaction.TransactionStatus.COMPLETED);
        assertThat(accountRepository.findByAccountNumber(toAcc).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("500"));
        assertThat(fraudAlertRepository.count()).isZero();
    }
}
