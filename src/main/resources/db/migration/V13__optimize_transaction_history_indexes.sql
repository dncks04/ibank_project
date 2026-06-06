-- 거래내역 조회 최적화.
-- 조회 패턴: (from_account_id = :id OR to_account_id = :id)
--            AND created_at 범위 필터 ORDER BY created_at DESC
-- 단일 컬럼 인덱스로는 created_at 정렬/범위를 인덱스로 처리하지 못해 정렬·필터가 비효율적이다.
-- (계좌, created_at DESC) 복합 인덱스로 교체하면 from/to 각각의 인덱스 스캔이
-- 정렬·범위까지 커버하고, OR 조건은 두 인덱스의 bitmap OR로 결합된다.
-- 복합 인덱스의 선두 컬럼이 FK 컬럼이므로 기존 단일 컬럼 인덱스는 중복 → 제거.
DROP INDEX IF EXISTS idx_transaction_from_account;
DROP INDEX IF EXISTS idx_transaction_to_account;

CREATE INDEX idx_transaction_from_account_created ON transactions (from_account_id, created_at DESC);
CREATE INDEX idx_transaction_to_account_created ON transactions (to_account_id, created_at DESC);
