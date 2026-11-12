-- 거래 이벤트 Outbox.
--
-- 거래·계좌의 상태가 바뀌는 바로 그 DB 트랜잭션 안에서 이벤트를 기록한다. 상태 변경과 이벤트가 함께 커밋되거나
-- 함께 롤백되므로, "돈은 움직였는데 이벤트는 없다"거나 그 반대인 경우가 생기지 않는다.
-- FDS 서비스는 GET /internal/events?after={id}로 마지막으로 처리한 id 이후의 이벤트를 주기적으로 가져간다.
--
-- ## id를 기록 시점이 아니라 공개 시점에 매기는 이유
--
-- 시퀀스 값은 커밋 순서가 아니라 insert 순서로 발급된다. 트랜잭션 A가 먼저 insert(작은 번호)하고 늦게 커밋하면,
-- 그사이 B(큰 번호)가 먼저 커밋돼 소비자에게 전달된다. 소비자가 "B까지 받았다"고 기억한 뒤 A가 커밋되면
-- A의 이벤트는 after 조건에 걸려 영영 전달되지 않는다.
--
-- 그래서 두 번호를 나눈다.
--   seq : insert 순서 (내부용 PK)
--   id  : 공개 순서. 처음엔 NULL이고, 공개 단계에서 1씩 증가하는 번호를 매긴다. 소비자는 이 값만 본다.
--
-- 공개 단계는 "아직 진행 중인 어떤 트랜잭션보다도 앞선 이벤트"에만 번호를 매긴다:
--   xid < pg_snapshot_xmin(pg_current_snapshot())
-- xmin보다 작은 트랜잭션은 모두 끝났으므로(커밋 또는 롤백), 그 범위에 뒤늦게 끼어들 이벤트는 없다.
-- 그 범위를 (xid, seq) 순으로 정렬해 번호를 매기면, 한 번 id=N까지 공개된 뒤 N 이하의 이벤트가 새로 나타나는 일이 없다.
--
-- ## 한계
--
-- xmin은 DB 전체에서 가장 오래된 "쓰기 트랜잭션"이 정한다. 어떤 트랜잭션이든(Outbox와 무관해도) 쓰기를 한 뒤
-- 오래 열려 있으면, 그 뒤에 기록된 이벤트는 그 트랜잭션이 끝날 때까지 공개되지 않는다.
-- 읽기만 하는 트랜잭션은 트랜잭션 ID를 받지 않으므로 영향을 주지 않는다.
CREATE TABLE outbox_events
(
    seq          BIGSERIAL   NOT NULL,
    id           BIGINT,
    xid          xid8        NOT NULL DEFAULT pg_current_xact_id(),
    event_type   VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMP   NOT NULL,

    CONSTRAINT pk_outbox_events PRIMARY KEY (seq),
    CONSTRAINT uq_outbox_events_id UNIQUE (id)
);

-- 공개 대기 중인 이벤트를 (xid, seq) 순으로 찾는다. 공개된 행은 인덱스에서 빠지므로 작게 유지된다.
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (xid, seq) WHERE id IS NULL;
-- 보관 기간이 지난 이벤트 정리용
CREATE INDEX idx_outbox_events_created ON outbox_events (created_at);

-- 마지막으로 매긴 공개 번호. 행이 하나뿐이며, 이 행의 락이 공개 단계를 한 번에 하나로 직렬화한다.
-- 시퀀스 대신 이 행을 쓰는 이유: 번호 부여와 이벤트 갱신이 한 트랜잭션으로 묶여 번호에 구멍이 생기지 않고,
-- 오래된 이벤트를 지워도 번호가 되돌아가지 않는다.
CREATE TABLE outbox_publish_state
(
    singleton BOOLEAN NOT NULL DEFAULT TRUE,
    last_id   BIGINT  NOT NULL,

    CONSTRAINT pk_outbox_publish_state PRIMARY KEY (singleton),
    CONSTRAINT ck_outbox_publish_state_singleton CHECK (singleton)
);

INSERT INTO outbox_publish_state (singleton, last_id)
VALUES (TRUE, 0);
