-- 값 무결성의 DB 차원 최후 방어선(CHECK 제약).
-- 잔액/금액 불변식이 애플리케이션 코드에만 의존하지 않도록, 코드 버그·배치·수동 SQL로도
-- 깨질 수 없게 데이터베이스가 직접 강제한다.

-- 계좌 잔액은 음수가 될 수 없다 (초과 인출 방지의 최후 방어선).
ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_balance_non_negative CHECK (balance >= 0);

-- 낙관적 락 버전은 음수가 될 수 없다.
ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_version_non_negative CHECK (version >= 0);

-- 거래 금액은 항상 양수.
ALTER TABLE transactions
    ADD CONSTRAINT ck_transactions_amount_positive CHECK (amount > 0);

-- 원장 금액은 항상 양수이며(차변/대변 방향으로 부호 결정), 방향 값은 CREDIT/DEBIT만 허용.
-- 잘못된 direction은 정합성 합계(SUM(CREDIT)-SUM(DEBIT))를 오염시키므로 DB에서 차단한다.
ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_entries_amount_positive CHECK (amount > 0);
ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_entries_direction CHECK (direction IN ('CREDIT', 'DEBIT'));
