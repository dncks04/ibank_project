-- 내부 API(서비스 간 호출)의 멱등성 키.
--
-- 상태를 바꾸는 내부 API 요청은 Idempotency-Key 헤더를 받는다. 처리에 성공하면 키와 응답을 여기에 저장하고,
-- 같은 키가 다시 오면 처리하지 않고 저장된 응답을 그대로 돌려준다(호출 서비스가 응답을 못 받고 재시도하는 경우).
--
-- request_fingerprint: 동작·대상·본문의 해시. 같은 키가 다른 요청에 다시 쓰이면(예: 실행에 쓴 키로 취소 요청)
--                      지문이 달라 409로 거부한다. 지문이 없으면 그런 요청에 엉뚱한 응답을 돌려주게 된다.
-- response_body      : 처음 성공했을 때의 응답(JSON). 재요청 시점에 상태를 다시 읽어 응답을 만들면
--                      그사이 상태가 바뀐 경우 처음과 다른 응답이 나가므로, 응답 자체를 저장한다.
--
-- 키 행은 업무 처리와 같은 트랜잭션에서 insert된다. 따로 커밋하면 "처리는 됐는데 키는 없는" 틈이 생겨
-- 재시도가 이미 처리된 요청을 다시 실행하려 든다.
-- 실패 응답은 저장하지 않는다(트랜잭션이 롤백되므로 키도 남지 않는다). 같은 키로 재시도하면 다시 평가된다.
CREATE TABLE internal_idempotency_keys
(
    idempotency_key     VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    operation           VARCHAR(40) NOT NULL,
    client_id           VARCHAR(64),
    response_body       TEXT        NOT NULL,
    created_at          TIMESTAMP   NOT NULL,

    CONSTRAINT pk_internal_idempotency_keys PRIMARY KEY (idempotency_key)
);

-- 보관 기간이 지난 키 정리용
CREATE INDEX idx_internal_idempotency_keys_created ON internal_idempotency_keys (created_at);

-- 보류 거래를 실행·취소할 때 호출 서비스가 보낸 사유 코드(예: GUARDIAN_APPROVED, CUSTOMER_CONFIRMED_SCAM).
ALTER TABLE transactions
    ADD COLUMN resolution_reason VARCHAR(50);
