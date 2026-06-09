package com.ibank.audit;

import com.ibank.domain.account.entity.Account;
import com.ibank.domain.account.repository.AccountRepository;
import com.ibank.domain.account.service.AccountAccessDeniedException;
import com.ibank.domain.ledger.repository.LedgerEntryRepository;
import com.ibank.domain.transaction.dto.DepositRequest;
import com.ibank.domain.transaction.dto.TransferRequest;
import com.ibank.domain.transaction.repository.TransactionRepository;
import com.ibank.domain.transaction.service.TransferService;
import com.ibank.domain.user.entity.User;
import com.ibank.domain.user.repository.UserRepository;
import com.ibank.global.audit.AuditLog;
import com.ibank.global.audit.AuditLogRepository;
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
 * 감사 로그(AOP) 검증. @Audited 메서드 실행 시 성공/실패가 audit_logs에 기록되는지 확인한다.
 * 특히 실패 시에도(비즈니스 트랜잭션 롤백) REQUIRES_NEW로 감사 기록이 남아야 한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AuditLogTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TransferService transferService;
    @Autowired UserRepository userRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired AuditLogRepository auditLogRepository;

    private static final String ACC = "50260101000001";
    private static final String ACC2 = "50260101000002";
    private Long ownerId;
    private Long otherId;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        User owner = userRepository.save(User.builder()
                .loginId("audit-owner").password("x").name("계좌주")
                .email("audit-owner@test.com").role(User.UserRole.ROLE_USER).build());
        ownerId = owner.getId();

        User other = userRepository.save(User.builder()
                .loginId("audit-other").password("x").name("타인")
                .email("audit-other@test.com").role(User.UserRole.ROLE_USER).build());
        otherId = other.getId();

        accountRepository.save(Account.builder()
                .accountNumber(ACC).owner(owner)
                .initialBalance(new BigDecimal("10000")).build());

        accountRepository.save(Account.builder()
                .accountNumber(ACC2).owner(other)
                .initialBalance(new BigDecimal("0")).build());
    }

    @Test
    @DisplayName("성공한 입금은 SUCCESS 감사 로그로 남는다")
    void deposit_success_recordsAudit() {
        transferService.depositForUser(ownerId,
                new DepositRequest(ACC, new BigDecimal("1000"), UUID.randomUUID().toString(), "감사 테스트"));

        assertThat(auditLogRepository.findAll())
                .anyMatch(a -> "DEPOSIT".equals(a.getAction())
                        && "SUCCESS".equals(a.getResult())
                        && ACC.equals(a.getTarget()));
    }

    @Test
    @DisplayName("실패한 작업도 FAILURE 감사 로그로 남는다 (트랜잭션 롤백과 독립)")
    void deposit_failure_recordsAudit() {
        // 타인 계좌 입금 시도 → 403 예외로 비즈니스 트랜잭션은 롤백되지만 감사는 남아야 한다
        assertThatThrownBy(() -> transferService.depositForUser(otherId,
                new DepositRequest(ACC, new BigDecimal("1000"), UUID.randomUUID().toString(), null)))
                .isInstanceOf(AccountAccessDeniedException.class);

        AuditLog failure = auditLogRepository.findAll().stream()
                .filter(a -> "DEPOSIT".equals(a.getAction()) && "FAILURE".equals(a.getResult()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("FAILURE 감사 로그가 없습니다"));

        assertThat(failure.getDetail()).isEqualTo("AccountAccessDeniedException");
        assertThat(failure.getTarget()).isEqualTo(ACC);
    }

    @Test
    @DisplayName("이체 감사 로그 target에는 출금→입금 계좌가 모두 남는다")
    void transfer_success_recordsBothAccountsInTarget() {
        transferService.transfer(ownerId, new TransferRequest(
                UUID.randomUUID().toString(), ACC, ACC2, new BigDecimal("1000"), "이체 감사 테스트"));

        assertThat(auditLogRepository.findAll())
                .anyMatch(a -> "TRANSFER".equals(a.getAction())
                        && "SUCCESS".equals(a.getResult())
                        && (ACC + "→" + ACC2).equals(a.getTarget()));
    }
}
