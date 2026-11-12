package com.ibank.internal.event;

import com.ibank.domain.outbox.OutboxPublisher;
import com.ibank.global.time.BusinessClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Outbox 이벤트 조회. 소비자(FDS 서비스)는 마지막으로 처리한 id를 기억해 두고 그 뒤의 이벤트를 가져간다.
 *
 * <p>조회할 때마다 먼저 공개 단계를 돌려, 그사이 공개해도 안전해진 이벤트에 번호를 매긴다. 따로 스케줄러를 두지
 * 않으므로 소비자가 묻는 만큼만 공개 작업이 일어난다. 공개는 번호를 한 번 매기면 끝이라, 같은 이벤트를 다시
 * 조회하는 것은 순수한 읽기다(at-least-once: 소비자가 같은 after로 다시 물으면 같은 이벤트가 다시 내려간다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalEventService {

    /** 한 번의 조회에서 공개할 최대 건수. 밀린 이벤트가 많아도 조회 한 번이 오래 걸리지 않게 한다. */
    private static final int PUBLISH_BATCH = 1_000;

    private final OutboxPublisher publisher;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final BusinessClock businessClock;

    public InternalEventsResponse read(long after, int limit) {
        try {
            publisher.publishPending(PUBLISH_BATCH);
        } catch (PessimisticLockingFailureException e) {
            // 다른 조회가 공개 단계를 쥐고 있어 기다리다 포기했다. 이미 공개된 것만 돌려주면 된다.
            log.warn("Outbox 공개 단계 락 대기 시간 초과 — 이번 조회는 이미 공개된 이벤트만 반환");
        }

        // 한 건 더 읽어 뒤에 더 있는지 판단한다
        List<InternalEventsResponse.Event> rows = jdbcTemplate.query("""
                SELECT id, event_type, aggregate_id, payload::text AS payload, created_at
                FROM outbox_events
                WHERE id > ?
                ORDER BY id
                LIMIT ?
                """,
                (rs, rowNum) -> new InternalEventsResponse.Event(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        rs.getString("aggregate_id"),
                        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                                businessClock.toBusinessOffset(rs.getTimestamp("created_at").toLocalDateTime())),
                        parse(rs.getString("payload"))),
                after, limit + 1);

        boolean hasMore = rows.size() > limit;
        List<InternalEventsResponse.Event> events = hasMore ? rows.subList(0, limit) : rows;
        long nextAfter = events.isEmpty() ? after : events.get(events.size() - 1).id();
        return new InternalEventsResponse(events, nextAfter, hasMore);
    }

    private JsonNode parse(String payload) {
        return objectMapper.readTree(payload);
    }
}
