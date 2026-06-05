-- 불변(append-only) 원장. 모든 잔액 변동을 차변(DEBIT)/대변(CREDIT)으로 기록한다.
-- 계좌 잔액(accounts.balance)은 성능을 위한 running snapshot이며,
-- 정합성은 balance == SUM(CREDIT) - SUM(DEBIT) 로 검증(reconciliation)할 수 있다.
CREATE TABLE ledger_entries
(
    id             BIGSERIAL      NOT NULL,
    transaction_id BIGINT,                       -- 거래 기반 항목. 개설 시 초기 잔액 항목은 NULL
    account_id     BIGINT         NOT NULL,
    direction      VARCHAR(10)    NOT NULL,       -- CREDIT | DEBIT
    amount         NUMERIC(19, 2) NOT NULL,       -- 항상 양수
    balance_after  NUMERIC(19, 2) NOT NULL,       -- 이 항목 반영 후 계좌 잔액
    created_at     TIMESTAMP      NOT NULL,

    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT fk_ledger_entries_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_ledger_entries_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id)
);

CREATE INDEX idx_ledger_entries_account_id ON ledger_entries (account_id);
CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries (transaction_id);
