package com.ibank.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V28(기존 이상거래 경보 제거) 마이그레이션이 이미 쌓여 있는 데이터를 어떻게 바꾸는지 확인한다.
 *
 * <p>데이터를 고치는 마이그레이션이라 빈 DB에서 도는 평소 테스트로는 검증되지 않는다.
 * V27까지만 올린 DB에 "옛 방식으로 보류된 거래"와 "새 방식으로 보류된 거래"를 직접 넣고,
 * 끝까지 올린 뒤 옛 방식 거래만 취소됐는지 본다.
 */
@Testcontainers(disabledWithoutDocker = true)
class LegacyFraudRemovalMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    @DisplayName("V28: 금액을 묶지 않은 옛 보류 거래만 취소되고, 홀드가 있는 보류 거래와 잔액은 그대로다")
    void legacyHeldTransactions_areCancelled() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 1) V27까지: fraud_alerts가 아직 있고 account_holds도 있는 시점
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target("27").load().migrate();

        jdbc.update("""
                INSERT INTO users (login_id, password, name, email, email_bidx, role, created_at)
                VALUES ('migrate-user', 'x', '고객', 'cipher', 'bidx', 'ROLE_USER', now())
                """);
        Long userId = jdbc.queryForObject("SELECT id FROM users", Long.class);
        jdbc.update("""
                INSERT INTO accounts (account_number, user_id, balance, status, created_at)
                VALUES ('20260101000001', ?, 1000000, 'ACTIVE', now()), ('20260101000002', ?, 0, 'ACTIVE', now())
                """, userId, userId);
        Long from = jdbc.queryForObject("SELECT id FROM accounts WHERE account_number = '20260101000001'", Long.class);
        Long to = jdbc.queryForObject("SELECT id FROM accounts WHERE account_number = '20260101000002'", Long.class);

        Long legacyHeld = insertTransaction(jdbc, "legacy-held", from, to, "300000", "HELD");
        Long newHeld = insertTransaction(jdbc, "new-held", from, to, "200000", "HELD");
        Long completed = insertTransaction(jdbc, "completed", from, to, "1000", "COMPLETED");
        // 새 방식의 보류 거래만 홀드를 가진다
        jdbc.update("""
                INSERT INTO account_holds (account_id, transaction_id, amount, status, expires_at, created_at, updated_at)
                VALUES (?, ?, 200000, 'ACTIVE', now() + INTERVAL '1 day', now(), now())
                """, from, newHeld);
        // 옛 방식의 보류 거래에는 관리자 검토용 경보가 붙어 있었다
        jdbc.update("""
                INSERT INTO fraud_alerts (account_id, transaction_id, triggered_rules, amount, detail, status, created_at)
                VALUES (?, ?, 'NEW_PAYEE_HIGH_VALUE', 300000, '보류', 'OPEN', now())
                """, from, legacyHeld);

        // 2) 끝까지 올린다
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        // 옛 보류 거래: 돈이 묶인 적이 없으므로 취소로 확정
        assertThat(jdbc.queryForObject("SELECT status FROM transactions WHERE id = ?", String.class, legacyHeld))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT resolution_reason FROM transactions WHERE id = ?", String.class, legacyHeld))
                .isEqualTo("LEGACY_HOLD_WITHOUT_FUNDS");
        // 새 보류 거래: 그대로 HELD, 홀드도 ACTIVE
        assertThat(jdbc.queryForObject("SELECT status FROM transactions WHERE id = ?", String.class, newHeld))
                .isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT status FROM account_holds WHERE transaction_id = ?", String.class, newHeld))
                .isEqualTo("ACTIVE");
        // 완료 거래와 잔액은 건드리지 않는다
        assertThat(jdbc.queryForObject("SELECT status FROM transactions WHERE id = ?", String.class, completed))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, from))
                .isEqualByComparingTo("1000000");
        // 경보 테이블은 사라졌다
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'fraud_alerts'", Long.class))
                .isZero();
        // 마이그레이션 뒤 불변식: 보류·판정 대기 거래는 모두 ACTIVE 홀드를 가진다
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM transactions t
                LEFT JOIN account_holds h ON h.transaction_id = t.id
                WHERE (t.status IN ('PENDING_REVIEW', 'HELD')) <> (h.status IS NOT DISTINCT FROM 'ACTIVE')
                """, Long.class)).isZero();
    }

    private Long insertTransaction(JdbcTemplate jdbc, String key, Long from, Long to, String amount, String status) {
        jdbc.update("""
                INSERT INTO transactions (idempotency_key, from_account_id, to_account_id, amount, type, status, created_at)
                VALUES (?, ?, ?, ?, 'TRANSFER', ?, now())
                """, key, from, to, new BigDecimal(amount), status);
        return jdbc.queryForObject("SELECT id FROM transactions WHERE idempotency_key = ?", Long.class, key);
    }
}
