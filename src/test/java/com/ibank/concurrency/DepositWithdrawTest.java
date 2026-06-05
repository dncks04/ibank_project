package com.ibank.concurrency;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.domain.account.entity.InsufficientBalanceException;
import com.ibank.domain.transaction.dto.DepositRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.dto.WithdrawRequest;
import com.ibank.domain.transaction.entity.Transaction;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DepositWithdrawTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private TransferService transferService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String ACC = "40260101000001";
    private Long ownerUserId;
    private Long otherUserId;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        User owner = userRepository.save(User.builder()
                .loginId("dw-owner").password(passwordEncoder.encode("pass"))
                .name("계좌주").email("owner@test.com").role(User.UserRole.ROLE_USER).build());
        ownerUserId = owner.getId();

        User other = userRepository.save(User.builder()
                .loginId("dw-other").password(passwordEncoder.encode("pass"))
                .name("타인").email("other@test.com").role(User.UserRole.ROLE_USER).build());
        otherUserId = other.getId();

        accountRepository.save(Account.builder()
                .accountNumber(ACC).owner(owner)
                .initialBalance(new BigDecimal("10000")).build());
    }

    @AfterEach
    void tearDown() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ── 입금 ──────────────────────────────────────────────

    @Test
    @DisplayName("입금 성공 - 잔액이 정확히 증가한다")
    void deposit_success_balanceIncreased() {
        TransferResponse response = transferService.depositForUser(ownerUserId,
                new DepositRequest(ACC, new BigDecimal("5000"), UUID.randomUUID().toString(), "입금 테스트"));

        Account account = accountRepository.findByAccountNumber(ACC).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("15000"));
        assertThat(response.toAccountNumber()).isEqualTo(ACC);
        assertThat(response.status()).isEqualTo(Transaction.TransactionStatus.COMPLETED);
    }

    @Test
    @DisplayName("입금 멱등성 - 같은 키로 두 번 요청해도 한 번만 반영된다")
    void deposit_idempotency_processedOnlyOnce() {
        String key = UUID.randomUUID().toString();
        DepositRequest request = new DepositRequest(ACC, new BigDecimal("3000"), key, "멱등성 테스트");

        transferService.depositForUser(ownerUserId, request);
        transferService.depositForUser(ownerUserId, request);

        Account account = accountRepository.findByAccountNumber(ACC).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("13000"));
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("타인 계좌 입금 시도 - 403 예외 발생")
    void deposit_otherUserAccount_throwsForbidden() {
        assertThatThrownBy(() ->
                transferService.depositForUser(otherUserId,
                        new DepositRequest(ACC, new BigDecimal("1000"), UUID.randomUUID().toString(), null))
        ).isInstanceOf(AccountAccessDeniedException.class);

        // 잔액 불변
        Account account = accountRepository.findByAccountNumber(ACC).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("10000"));
    }

    // ── 출금 ──────────────────────────────────────────────

    @Test
    @DisplayName("출금 성공 - 잔액이 정확히 감소한다")
    void withdraw_success_balanceDecreased() {
        TransferResponse response = transferService.withdraw(ownerUserId,
                new WithdrawRequest(ACC, new BigDecimal("4000"), UUID.randomUUID().toString(), "출금 테스트"));

        Account account = accountRepository.findByAccountNumber(ACC).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("6000"));
        assertThat(response.fromAccountNumber()).isEqualTo(ACC);
        assertThat(response.status()).isEqualTo(Transaction.TransactionStatus.COMPLETED);
    }

    @Test
    @DisplayName("잔액 초과 출금 - InsufficientBalanceException 발생, 잔액 불변")
    void withdraw_insufficientBalance_throwsException() {
        assertThatThrownBy(() ->
                transferService.withdraw(ownerUserId,
                        new WithdrawRequest(ACC, new BigDecimal("99999"), UUID.randomUUID().toString(), null))
        ).isInstanceOf(InsufficientBalanceException.class);

        Account account = accountRepository.findByAccountNumber(ACC).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("10000"));
    }

    @Test
    @DisplayName("출금 멱등성 - 같은 키로 두 번 요청해도 한 번만 반영된다")
    void withdraw_idempotency_processedOnlyOnce() {
        String key = UUID.randomUUID().toString();
        WithdrawRequest request = new WithdrawRequest(ACC, new BigDecimal("2000"), key, "멱등성 출금");

        transferService.withdraw(ownerUserId, request);
        transferService.withdraw(ownerUserId, request);

        Account account = accountRepository.findByAccountNumber(ACC).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("8000"));
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("타인 계좌 출금 시도 - 403 예외 발생")
    void withdraw_otherUserAccount_throwsForbidden() {
        assertThatThrownBy(() ->
                transferService.withdraw(otherUserId,
                        new WithdrawRequest(ACC, new BigDecimal("1000"), UUID.randomUUID().toString(), null))
        ).isInstanceOf(AccountAccessDeniedException.class);

        Account account = accountRepository.findByAccountNumber(ACC).orElseThrow();
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("10000"));
    }
}
