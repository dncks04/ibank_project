-- FDS-lite: 룰 기반 이상거래 탐지 결과.
--
-- 이체가 의심 룰(velocity·심야 고액·신규 수취인 고액 등)에 걸리면 즉시 완료하지 않고
-- HELD 상태로 보류하면서 여기에 경보를 남긴다. 운영자는 이 테이블을 검토 큐로 사용한다.
-- (자금은 이동하지 않고 출금 계좌에 그대로 남으므로 정산 불변식에 영향이 없다.)
CREATE TABLE fraud_alerts (
    id              BIGSERIAL PRIMARY KEY,
    account_id      BIGINT       NOT NULL REFERENCES accounts (id),
    -- 보류된 거래. 거래를 만들지 않는 평가 경로에서는 NULL일 수 있다.
    transaction_id  BIGINT       REFERENCES transactions (id),
    -- 발동한 룰 목록(쉼표 구분). 예: "VELOCITY,NIGHT_HIGH_VALUE"
    triggered_rules VARCHAR(200) NOT NULL,
    amount          NUMERIC(19, 2) NOT NULL,
    detail          VARCHAR(500),
    -- OPEN(검토 대기) / RESOLVED(처리됨)
    status          VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMP    NOT NULL
);

-- 검토 큐 조회: 미처리(OPEN) 경보를 최신순으로.
CREATE INDEX idx_fraud_alerts_status_created ON fraud_alerts (status, created_at DESC);
