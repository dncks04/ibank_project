package com.ibank.idempotency;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.dto.TransferResponse;
import com.ibank.domain.transaction.exception.IdempotencyKeyConflictException;
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

/**
 * 멱등성 키 + 요청 지문(fingerprint) 검증.
 *
 * 같은 키로 들어온 재요청이 정말 동일한 요청일 때만 기존 결과를 replay하고,
 * 같은 키에 다른 내용(금액/계좌)이 실리면 옛 결과를 조용히 반환하지 않고 충돌로 거부하는지 검증한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class TransferIdempotencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private TransferService transferService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String ACC_A = "20260201000001";
    private static final String ACC_B = "20260201000002";

    private Long userId;

    @BeforeEach
    void setUp() {
        cleanUp();
        User user = userRepository.save(User.builder()
                .loginId("idempotency-user")
                .password(passwordEncoder.encode("pass"))
                .name("테스트").email("idempotency@test.com")
                .role(User.UserRole.ROLE_USER).build());
        userId = user.getId();

        accountRepository.save(Account.builder()
                .accountNumber(ACC_A).owner(user)
                .initialBalance(new BigDecimal("100000")).build());
        accountRepository.save(Account.builder()
                .accountNumber(ACC_B).owner(user)
                .initialBalance(BigDecimal.ZERO).build());
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("같은 키 + 같은 내용 재요청 - 한 번만 처리되고 동일 결과를 replay한다")
    void sameKey_sameContent_replaysOnce() {
        String key = UUID.randomUUID().toString();
        TransferRequest request = new TransferRequest(key, ACC_A, ACC_B, new BigDecimal("1000"), "멱등");

        TransferResponse first = transferService.transfer(userId, request);
        TransferResponse second = transferService.transfer(userId, request);

        // 두 응답은 동일한 거래를 가리킨다 (새 거래가 생기지 않음)
        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(transactionRepository.count()).isEqualTo(1);

        // 잔액은 1건만 반영
        assertThat(balanceOf(ACC_A)).isEqualByComparingTo("99000");
        assertThat(balanceOf(ACC_B)).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("같은 키 + 다른 금액 - 옛 결과를 replay하지 않고 409로 거부한다")
    void sameKey_differentAmount_rejectedWithConflict() {
        String key = UUID.randomUUID().toString();
        transferService.transfer(userId, new TransferRequest(key, ACC_A, ACC_B, new BigDecimal("1000"), "최초"));

        // 같은 키로 금액만 100만원으로 바꿔 재요청 → 조용한 replay가 아니라 충돌로 거부되어야 한다
        assertThatThrownBy(() -> transferService.transfer(userId,
                new TransferRequest(key, ACC_A, ACC_B, new BigDecimal("1000000"), "변조")))
                .isInstanceOf(IdempotencyKeyConflictException.class);

        // 두 번째 요청은 처리되지 않았다: 거래 1건, 잔액은 최초 1,000원만 반영
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(balanceOf(ACC_A)).isEqualByComparingTo("99000");
        assertThat(balanceOf(ACC_B)).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("같은 키 + 다른 입금 계좌 - 409로 거부한다")
    void sameKey_differentToAccount_rejectedWithConflict() {
        String key = UUID.randomUUID().toString();
        BigDecimal amount = new BigDecimal("1000");
        transferService.transfer(userId, new TransferRequest(key, ACC_A, ACC_B, amount, "최초"));

        assertThatThrownBy(() -> transferService.transfer(userId,
                new TransferRequest(key, ACC_B, ACC_A, amount, "방향변조")))
                .isInstanceOf(IdempotencyKeyConflictException.class);

        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("금액 표기 차이(1000 vs 1000.00)는 동일 요청으로 간주되어 replay된다")
    void sameKey_amountScaleDiffers_stillReplays() {
        String key = UUID.randomUUID().toString();
        TransferResponse first = transferService.transfer(userId,
                new TransferRequest(key, ACC_A, ACC_B, new BigDecimal("1000"), "정수표기"));
        TransferResponse second = transferService.transfer(userId,
                new TransferRequest(key, ACC_A, ACC_B, new BigDecimal("1000.00"), "소수표기"));

        assertThat(second.transactionId()).isEqualTo(first.transactionId());
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(balanceOf(ACC_A)).isEqualByComparingTo("99000");
    }

    private BigDecimal balanceOf(String accountNumber) {
        return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
    }
}
