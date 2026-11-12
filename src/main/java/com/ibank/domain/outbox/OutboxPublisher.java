package com.ibank.domain.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 단계: 기록된 이벤트 중 공개해도 안전한 것에 공개 번호({@code id})를 순서대로 매긴다.
 *
 * <p>"안전하다"는 것은 그 이벤트보다 앞에 끼어들 이벤트가 더는 없다는 뜻이다. 아직 진행 중인 트랜잭션 가운데
 * 가장 오래된 것의 ID가 {@code xmin}이고, xmin보다 작은 트랜잭션은 모두 끝났다. 그러므로 {@code xid < xmin}인
 * 이벤트의 집합은 더 늘어나지 않는다. 그 집합을 (xid, seq) 순으로 번호 매기면, 다음 공개분은 언제나 그보다
 * 큰 xid의 이벤트라 더 큰 번호를 받는다.
 *
 * <p>공개 단계는 한 번에 하나만 돈다({@code outbox_publish_state} 행 락). 두 공개가 겹치면 같은 이벤트에
 * 번호를 두 번 매기거나 순서가 엇갈릴 수 있다.
 *
 * <p>READ_COMMITTED여야 한다. 락을 얻은 <b>뒤의</b> 문장이 새 스냅샷을 잡아야, 락을 기다리는 동안 앞선 공개가
 * 번호를 매긴 이벤트를 다시 집지 않는다.
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param max 한 번에 공개할 최대 건수
     * @return 이번에 공개한 건수
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int publishPending(int max) {
        // 1) 공개 단계 직렬화 + 마지막 번호 읽기
        Long lastId = jdbcTemplate.queryForObject(
                "SELECT last_id FROM outbox_publish_state WHERE singleton FOR UPDATE", Long.class);

        // 2) 공개해도 안전한 이벤트에 lastId+1부터 번호를 매긴다 (xid, seq 순)
        int published = jdbcTemplate.update("""
                WITH safe AS (
                    SELECT seq, row_number() OVER (ORDER BY xid, seq) AS rn
                    FROM outbox_events
                    WHERE id IS NULL
                      AND xid < pg_snapshot_xmin(pg_current_snapshot())
                    ORDER BY xid, seq
                    LIMIT ?
                )
                UPDATE outbox_events e
                SET id = ? + safe.rn
                FROM safe
                WHERE e.seq = safe.seq
                """, max, lastId);

        // 3) 마지막 번호 갱신. 2와 같은 트랜잭션이라 번호에 구멍이 생기지 않는다.
        if (published > 0) {
            jdbcTemplate.update("UPDATE outbox_publish_state SET last_id = ? WHERE singleton", lastId + published);
        }
        return published;
    }
}
