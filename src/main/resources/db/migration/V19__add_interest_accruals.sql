-- 이자 계산: 일일 적립(accrual) + 월말 지급(payment).
--
-- 일일 배치가 계좌별로 그날의 이자를 interest_accruals에 한 행씩 적립하고,
-- 월말 배치가 미지급 적립분을 합산해 계좌에 입금하면서 원장에 복식부기 분개를 남긴다.
-- (고객 계좌 CREDIT + INTEREST_EXPENSE 시스템 계정 DEBIT = 합 0인 분개).

-- 1) 이자 지급의 상대 leg가 귀속될 시스템 계정(INTEREST_EXPENSE)을 허용하도록 제약을 갱신한다.
--    은행이 부담하는 이자 비용 계정으로, DEBIT 잔액이 누적되는 것이 정상이다(분개 자체는 균형).
ALTER TABLE ledger_entries
    DROP CONSTRAINT ck_ledger_entries_system_account;
ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_entries_system_account
        CHECK (system_account IS NULL OR system_account IN ('CLEARING', 'INTEREST_EXPENSE'));

-- 2) 일일 이자 적립 원장.
--    (account_id, accrual_date) 유니크: 같은 날 같은 계좌의 중복 적립을 DB가 차단한다
--    → 일일 배치를 같은 날 다시 돌려도 멱등(중복 적립 없음).
CREATE TABLE interest_accruals (
    id               BIGSERIAL PRIMARY KEY,
    account_id       BIGINT        NOT NULL REFERENCES accounts (id),
    accrual_date     DATE          NOT NULL,
    balance_snapshot NUMERIC(19, 2) NOT NULL,
    annual_rate      NUMERIC(9, 6)  NOT NULL,
    interest_amount  NUMERIC(19, 2) NOT NULL,
    paid             BOOLEAN       NOT NULL DEFAULT FALSE,
    -- 지급 시 기록되는 원장 분개 id. 미지급분은 NULL.
    payment_journal_id VARCHAR(64),
    created_at       TIMESTAMP     NOT NULL,

    CONSTRAINT uq_interest_accruals_account_date UNIQUE (account_id, accrual_date),
    -- 적립 이자는 음수가 될 수 없다(잔액 0이면 0). 미지급이면 분개 id가 없어야 한다.
    CONSTRAINT ck_interest_accruals_amount_non_negative CHECK (interest_amount >= 0),
    CONSTRAINT ck_interest_accruals_paid_journal CHECK (paid = (payment_journal_id IS NOT NULL))
);

-- 월말 지급 배치가 "미지급 적립분"을 계좌별로 모으는 조회 경로.
CREATE INDEX idx_interest_accruals_unpaid ON interest_accruals (account_id) WHERE paid = FALSE;
