package com.ibank.account;

import com.ibank.domain.account.dto.AccountOpenRequest;
import com.ibank.domain.account.dto.AccountResponse;
import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.exception.AccountNotEmptyException;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.domain.account.service.AccountService;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
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
 * 계좌 개설/해지 멱등성 검증.
 *
 * 네트워크 재시도 등으로 같은 요청이 중복 도달해도, 개설은 계좌를 중복 생성하지 않고
 * 기존 계좌를 그대로 반환하며, 해지는 같은 키 재요청을 성공으로 흡수(no-op)하는지 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AccountIdempotencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired UserRepository userRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;

    private Long userId;
    private Long otherUserId;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(User.builder()
                .loginId("idem-user").password("x").name("멱등유저")
                .email("idem@test.com").role(User.UserRole.ROLE_USER).build());
        userId = user.getId();
        User other = userRepository.save(User.builder()
                .loginId("other-user").password("x").name("타인")
                .email("other@test.com").role(User.UserRole.ROLE_USER).build());
        otherUserId = other.getId();
    }

    @Test
    @DisplayName("같은 키로 계좌를 두 번 개설해도 계좌는 하나만 생성되고 동일 계좌가 반환된다")
    void openAccount_sameKey_isReplayed() {
        String key = UUID.randomUUID().toString();
        AccountResponse first = accountService.openAccount(userId,
                new AccountOpenRequest(key, new BigDecimal("10000")));
        AccountResponse second = accountService.openAccount(userId,
                new AccountOpenRequest(key, new BigDecimal("10000")));

        assertThat(second.accountNumber()).isEqualTo(first.accountNumber());
        assertThat(accountRepository.count()).isEqualTo(1);
        // 개설 분개는 고객 CREDIT + 클리어링 DEBIT 2건. replay는 이를 중복 기록하지 않는다.
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("다른 사용자가 같은 개설 키를 사용하면 접근이 거부된다 (키 도용 차단)")
    void openAccount_sameKeyDifferentUser_isForbidden() {
        String key = UUID.randomUUID().toString();
        accountService.openAccount(userId, new AccountOpenRequest(key, new BigDecimal("10000")));

        assertThatThrownBy(() -> accountService.openAccount(otherUserId,
                new AccountOpenRequest(key, new BigDecimal("10000"))))
                .isInstanceOf(AccountAccessDeniedException.class);
    }

    @Test
    @DisplayName("같은 키로 계좌를 두 번 해지해도 두 번째 요청은 성공으로 흡수된다 (no-op)")
    void closeAccount_sameKey_isIdempotent() {
        String acc = accountService.openAccount(userId,
                new AccountOpenRequest(UUID.randomUUID().toString(), BigDecimal.ZERO)).accountNumber();
        String closeKey = UUID.randomUUID().toString();

        accountService.closeAccount(acc, userId, closeKey);
        // 같은 키 재요청 → 예외 없이 성공
        accountService.closeAccount(acc, userId, closeKey);

        assertThat(accountRepository.findByAccountNumber(acc).orElseThrow().getStatus())
                .isEqualTo(Account.AccountStatus.CLOSED);
    }

    @Test
    @DisplayName("이미 해지된 계좌를 다른 키로 다시 해지하려 하면 거부된다")
    void closeAccount_differentKeyAfterClosed_isRejected() {
        String acc = accountService.openAccount(userId,
                new AccountOpenRequest(UUID.randomUUID().toString(), BigDecimal.ZERO)).accountNumber();
        accountService.closeAccount(acc, userId, UUID.randomUUID().toString());

        assertThatThrownBy(() -> accountService.closeAccount(acc, userId, UUID.randomUUID().toString()))
                .isInstanceOf(com.ibank.domain.account.exception.InactiveAccountException.class);
    }

    @Test
    @DisplayName("잔액이 남은 계좌는 해지할 수 없다")
    void closeAccount_nonZeroBalance_isRejected() {
        String acc = accountService.openAccount(userId,
                new AccountOpenRequest(UUID.randomUUID().toString(), new BigDecimal("5000"))).accountNumber();

        assertThatThrownBy(() -> accountService.closeAccount(acc, userId, UUID.randomUUID().toString()))
                .isInstanceOf(AccountNotEmptyException.class);
    }
}
