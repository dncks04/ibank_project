package com.ibank.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 테스트 간 DB 정리 헬퍼.
 *
 * <p>테스트마다 리포지토리 {@code deleteAll()}을 FK 순서대로 나열하던 방식을 대체한다.
 * 그 방식은 테이블이 하나 늘 때마다(예: 거래를 참조하는 홀드) 모든 테스트의 정리 순서를 고쳐야 하고,
 * 하나라도 빠뜨리면 FK 위반으로 무관한 테스트가 깨진다. 여기서는 TRUNCATE ... CASCADE로
 * 도메인 테이블을 한 번에 비우므로 순서를 신경 쓸 필요가 없다.
 *
 * <p>존재하는 테이블만 비운다 — 마이그레이션으로 테이블이 추가·삭제되어도 이 목록에 이름만 있으면 된다.
 * Spring Batch 메타데이터와 ShedLock 테이블은 도메인 데이터가 아니므로 건드리지 않는다.
 */
@Component
public class DatabaseCleaner {

    /** 사용자에 딸린 테이블 (사용자를 지울 때만 함께 비운다). */
    private static final List<String> USER_TABLES = List.of("refresh_tokens", "users");

    /** 사용자를 제외한 도메인 테이블. */
    private static final List<String> DOMAIN_TABLES = List.of(
            "outbox_events",
            "internal_idempotency_keys",
            "account_holds",
            "fraud_alerts",
            "interest_accruals",
            "reconciliation_results",
            "ledger_entries",
            "transactions",
            "accounts",
            "audit_logs");

    private final JdbcTemplate jdbcTemplate;

    public DatabaseCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 사용자까지 포함해 모든 도메인 데이터를 비운다. */
    public void clearAll() {
        truncate(concat(DOMAIN_TABLES, USER_TABLES));
    }

    /** 사용자(와 리프레시 토큰)는 남기고 계좌·거래·원장 등만 비운다. */
    public void clearExceptUsers() {
        truncate(DOMAIN_TABLES);
    }

    private void truncate(List<String> candidates) {
        List<String> existing = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()",
                String.class);
        List<String> targets = candidates.stream().filter(existing::contains).toList();
        if (targets.isEmpty()) {
            return;
        }
        // CASCADE는 여기 나열한 테이블을 참조하는 테이블까지 비운다. 식별자는 위 상수뿐이라 인젝션 여지가 없다.
        // 시퀀스는 되돌리지 않는다(deleteAll()과 같은 동작) — ID가 재사용되면 인증 캐시 등에 옛 값이 겹칠 수 있다.
        jdbcTemplate.execute("TRUNCATE TABLE " + String.join(", ", targets) + " CASCADE");
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
