package com.ibank.domain.outbox;

import com.ibank.global.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.Map;

/**
 * Outbox에 이벤트를 기록한다.
 *
 * <p>{@code MANDATORY}: 상태를 바꾸는 트랜잭션 안에서만 호출할 수 있다. 트랜잭션 밖에서 기록하면 상태 변경과
 * 이벤트가 따로 커밋되어, 한쪽만 남는 경우가 생긴다. JPA 트랜잭션과 같은 커넥션으로 insert되므로
 * (같은 DataSource) 업무 변경과 함께 커밋·롤백된다.
 *
 * <p>공개 번호({@code id})와 트랜잭션 ID({@code xid})는 여기서 넣지 않는다. xid는 DB 기본값이 채우고,
 * id는 {@link OutboxPublisher}가 공개 시점에 매긴다.
 */
@Component
@RequiredArgsConstructor
public class OutboxRecorder {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BusinessClock businessClock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(EventType type, String aggregateId, Map<String, Object> payload) {
        jdbcTemplate.update(
                "INSERT INTO outbox_events (event_type, aggregate_id, payload, created_at) VALUES (?, ?, ?::jsonb, ?)",
                type.name(),
                aggregateId,
                objectMapper.writeValueAsString(payload),
                Timestamp.valueOf(businessClock.nowForStorage()));
    }
}
