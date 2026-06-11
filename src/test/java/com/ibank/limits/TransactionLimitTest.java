package com.ibank.limits;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.transaction.dto.DepositRequest;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.exception.DailyLimitExceededException;
import com.ibank.domain.transaction.exception.TransactionLimitExceededException;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 거래 한도 검증. 테스트 전용으로 한도를 낮춰(단건 10,000 / 1일 출금 20,000)
 * 단건 상한·1일 누적 출금 한도·경계값·한도 미소모(거부 시) 동작을 확인한다.
 */
@SpringBootTest(properties = {
        "ibank.limits.per-transaction-max=10000",
        "ibank.limits.daily-outflow-max=20000"
})
@Testcontainers(disabledWithoutDocker = true)
class TransactionLimitTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TransferService transferService;
    @Autowired AccountService accountService;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;

    private Long userId;
    private String accA;
    private String accB;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(User.builder()
                .loginId("limit-user").password("x")
                .name("한도유저").email("limit@test.com")
                .role(User.UserRole.ROLE_USER).build());
        userId = user.getId();

        accA = accountService.openAccount(userId, new AccountOpenRequest(new BigDecimal("100000")))
                .accountNumber();
        accB = accountService.openAccount(userId, new AccountOpenRequest(BigDecimal.ZERO))
                .accountNumber();
    }

    @Test
    @DisplayName("단건 한도를 초과하는 이체·입금·출금은 거부되고 부수효과가 없다")
    void perTransactionLimit_rejectsAndLeavesNoSideEffects() {
        BigDecimal overLimit = new BigDecimal("10001");

        assertThatThrownBy(() -> transferService.transfer(userId, transfer(accA, accB, overLimit)))
                .isInstanceOf(TransactionLimitExceededException.class);
        assertThatThrownBy(() -> transferService.depositForUser(userId, deposit(accA, overLimit)))
                .isInstanceOf(TransactionLimitExceededException.class);
        assertThatThrownBy(() -> transferService.withdraw(userId, withdraw(accA, overLimit)))
                .isInstanceOf(TransactionLimitExceededException.class);

        // 거래·원장 미생성, 잔액 불변 (개설 분개 2건만 존재)
        assertThat(transactionRepository.count()).isZero();
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
        assertThat(balanceOf(accA)).isEqualByComparingTo(new BigDecimal("100000"));
    }

    @Test
    @DisplayName("단건 한도와 정확히 같은 금액은 허용된다 (경계 포함)")
    void perTransactionLimit_boundaryInclusive() {
        transferService.transfer(userId, transfer(accA, accB, new BigDecimal("10000")));

        assertThat(balanceOf(accB)).isEqualByComparingTo(new BigDecimal("10000"));
    }

    @Test
    @DisplayName("1일 누적 출금(이체+출금) 한도를 초과하면 거부되고, 거부된 요청은 한도를 소모하지 않는다")
    void dailyOutflowLimit_enforcedCumulatively() {
        transferService.transfer(userId, transfer(accA, accB, new BigDecimal("8000")));  // 누적 8000
        transferService.withdraw(userId, withdraw(accA, new BigDecimal("7000")));        // 누적 15000

        // 6000 이체 시 21000 > 20000 → 거부
        assertThatThrownBy(() -> transferService.transfer(userId, transfer(accA, accB, new BigDecimal("6000"))))
                .isInstanceOf(DailyLimitExceededException.class);

        // 거부된 요청은 한도를 소모하지 않으므로, 정확히 한도를 채우는 5000은 허용 (누적 20000 == 한도)
        transferService.transfer(userId, transfer(accA, accB, new BigDecimal("5000")));

        // 한도 소진 후에는 최소 금액도 거부
        assertThatThrownBy(() -> transferService.withdraw(userId, withdraw(accA, new BigDecimal("1"))))
                .isInstanceOf(DailyLimitExceededException.class);

        // 입금은 출금 한도를 소모하지 않는다
        transferService.depositForUser(userId, deposit(accA, new BigDecimal("500")));

        assertThat(balanceOf(accA)).isEqualByComparingTo(new BigDecimal("80500")); // 100000 - 20000 + 500
    }

    @Test
    @DisplayName("1일 출금 한도는 계좌 단위라서 다른 계좌의 한도에 영향을 주지 않는다")
    void dailyOutflowLimit_isPerAccount() {
        // A의 한도를 모두 소진
        transferService.transfer(userId, transfer(accA, accB, new BigDecimal("10000")));
        transferService.transfer(userId, transfer(accA, accB, new BigDecimal("10000")));
        assertThatThrownBy(() -> transferService.withdraw(userId, withdraw(accA, new BigDecimal("1"))))
                .isInstanceOf(DailyLimitExceededException.class);

        // B는 별도 한도로 정상 출금 (단건 한도 10000 이내)
        transferService.withdraw(userId, withdraw(accB, new BigDecimal("10000")));

        assertThat(balanceOf(accB)).isEqualByComparingTo(new BigDecimal("10000"));
    }

    private TransferRequest transfer(String from, String to, BigDecimal amount) {
        return new TransferRequest(UUID.randomUUID().toString(), from, to, amount, "한도 테스트");
    }

    private DepositRequest deposit(String accountNumber, BigDecimal amount) {
        return new DepositRequest(accountNumber, amount, UUID.randomUUID().toString(), "한도 테스트");
    }

    private WithdrawRequest withdraw(String accountNumber, BigDecimal amount) {
        return new WithdrawRequest(accountNumber, amount, UUID.randomUUID().toString(), "한도 테스트");
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }
}
