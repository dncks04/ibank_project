-- 홀드(보류 금액 묶기).
--
-- 판정을 기다리거나 보류된 거래의 금액을 출금 계좌에 묶어 둔다. 홀드는 원장 분개를 만들지 않는다:
-- 원장잔액(accounts.balance)은 그대로이고, 가용잔액 = 원장잔액 - ACTIVE 홀드 합계만 줄어든다.
-- 출금·이체의 잔액 검증은 가용잔액 기준이므로, 보류 중인 금액을 다른 출금으로 빼 갈 수 없다.
--
-- status
--   ACTIVE   : 금액이 묶여 있음 (가용잔액에서 차감)
--   CONSUMED : 거래가 실행되어 실제 출금으로 전환됨
--   RELEASED : 거래가 취소·실패하여 해제됨
--   EXPIRED  : 최대 보류 시간이 지나 해제됨
--
-- transaction_id UNIQUE: 거래 하나에 홀드는 하나뿐이다.
-- "ACTIVE 홀드 합계 <= 원장잔액"은 테이블 간 조건이라 CHECK로 걸 수 없다. 홀드 생성·소비·해제를
-- 모두 해당 계좌의 행 락 안에서 수행해 보장하고, 정산 배치가 사후 검증한다.
CREATE TABLE account_holds
(
    id             BIGSERIAL      NOT NULL,
    account_id     BIGINT         NOT NULL,
    transaction_id BIGINT         NOT NULL,
    amount         NUMERIC(19, 2) NOT NULL,
    status         VARCHAR(20)    NOT NULL,
    expires_at     TIMESTAMP      NOT NULL,
    created_at     TIMESTAMP      NOT NULL,
    updated_at     TIMESTAMP      NOT NULL,

    CONSTRAINT pk_account_holds PRIMARY KEY (id),
    CONSTRAINT uq_account_holds_transaction UNIQUE (transaction_id),
    CONSTRAINT fk_account_holds_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_account_holds_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT ck_account_holds_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_account_holds_status CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED', 'EXPIRED'))
);

-- 가용잔액 계산(계좌의 ACTIVE 홀드 합계)과 만료 배치는 ACTIVE 행만 본다. 종료된 홀드는 계속 쌓이므로 부분 인덱스로 둔다.
CREATE INDEX idx_account_holds_active_account ON account_holds (account_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_account_holds_active_expiry ON account_holds (expires_at) WHERE status = 'ACTIVE';
