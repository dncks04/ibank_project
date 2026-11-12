package com.ibank.domain.outbox;

import com.ibank.global.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;

/**
 * 보관 기간이 지난 Outbox 이벤트와 내부 API 멱등성 키의 정리.
 *
 * <p>이벤트는 <b>공개된 것만</b> 지운다. 공개되지 않은 이벤트는 아무리 오래됐어도 아직 소비자에게 전달된 적이
 * 없으므로 남겨 둔다. 소비자가 보관 기간보다 오래 뒤처지면 그 사이의 이벤트는 잃는다 — 보관 기간은 그 한도다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxMaintenanceService {

    private final JdbcTemplate jdbcTemplate;
    private final BusinessClock businessClock;

    /** @return 지운 이벤트 수 */
    @Transactional
    public int purgePublishedEventsOlderThan(Duration retention) {
        Timestamp cutoff = Timestamp.valueOf(businessClock.nowForStorage().minus(retention));
        int deleted = jdbcTemplate.update(
                "DELETE FROM outbox_events WHERE id IS NOT NULL AND created_at < ?", cutoff);
        log.info("Outbox 정리: {} 이전에 기록되어 공개된 이벤트 {}건 삭제", cutoff, deleted);
        return deleted;
    }

    /** @return 지운 키 수 */
    @Transactional
    public int purgeIdempotencyKeysOlderThan(Duration retention) {
        Timestamp cutoff = Timestamp.valueOf(businessClock.nowForStorage().minus(retention));
        int deleted = jdbcTemplate.update("DELETE FROM internal_idempotency_keys WHERE created_at < ?", cutoff);
        log.info("내부 API 멱등성 키 정리: {} 이전 {}건 삭제", cutoff, deleted);
        return deleted;
    }
}
