-- 복식부기 완성: 분개(journal) 단위 zero-sum 불변식 도입.
--
-- 기존에는 입금/출금/개설 초기 잔액이 고객 leg 1건만 기록되어 시스템 전체 원장 합이 0이 아니었다
-- (계좌별 잔액==원장합 검사는 통과해도, 돈이 생기거나 사라지는 버그는 탐지 불가).
-- 내부 시스템 계정(CLEARING)이 외부 경계 거래의 상대 leg를 지도록 하여
-- 모든 분개의 SUM(CREDIT) - SUM(DEBIT) == 0, 전 원장 시산표 == 0 을 성립시킨다.
--
-- 시스템 계정은 accounts 행이 아니라 ledger_entries.system_account 컬럼(enum)이다.
-- 잔액 행을 갱신하지 않고 leg만 insert하므로(insert-only) 모든 입출금이
-- 한 행을 잠그는 핫스팟 락 경합이 구조적으로 발생하지 않는다.

-- 1) 컬럼 추가: 분개 식별자 + 시스템 계정. 시스템 leg는 고객 계좌/잔액 스냅샷이 없으므로 NULL 허용으로 완화.
ALTER TABLE ledger_entries
    ADD COLUMN journal_id VARCHAR(64);
ALTER TABLE ledger_entries
    ADD COLUMN system_account VARCHAR(20);
ALTER TABLE ledger_entries
    ALTER COLUMN account_id DROP NOT NULL;
ALTER TABLE ledger_entries
    ALTER COLUMN balance_after DROP NOT NULL;

-- 2) 기존 데이터 백필: 이체 leg 2건은 transaction_id를 공유하므로 같은 분개로 묶이고,
--    거래 없는 개설 항목은 행별 고유 분개가 된다.
UPDATE ledger_entries
SET journal_id = COALESCE('TX-' || transaction_id, 'OPEN-' || id);

-- 3) 기존 단일 leg 분개(입금/출금/개설)에 CLEARING 상대 leg를 삽입해 과거 분개까지 균형화.
--    분개 합이 양수(순 CREDIT)면 DEBIT, 음수면 CREDIT 방향의 거울 leg를 만든다.
INSERT INTO ledger_entries (transaction_id, account_id, system_account, direction, amount,
                            balance_after, created_at, journal_id)
SELECT MAX(transaction_id),
       NULL,
       'CLEARING',
       CASE WHEN SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) > 0
            THEN 'DEBIT' ELSE 'CREDIT' END,
       ABS(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END)),
       NULL,
       MAX(created_at),
       journal_id
FROM ledger_entries
GROUP BY journal_id
HAVING SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END) <> 0;

-- 4) 제약 강제
ALTER TABLE ledger_entries
    ALTER COLUMN journal_id SET NOT NULL;

CREATE INDEX idx_ledger_entries_journal_id ON ledger_entries (journal_id);

-- 모든 leg는 고객 계좌 또는 시스템 계정 중 정확히 하나에 귀속된다.
ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_entries_one_holder CHECK ((account_id IS NULL) <> (system_account IS NULL));

-- 시스템 계정 값 제한 (SystemAccount enum과 동기화).
ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_entries_system_account CHECK (system_account IS NULL OR system_account IN ('CLEARING'));

-- 잔액 스냅샷은 고객 leg에만 존재한다.
ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_entries_balance_after CHECK ((account_id IS NOT NULL) = (balance_after IS NOT NULL));
