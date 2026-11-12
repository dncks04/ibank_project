-- 2단계 거래 처리(가승인 → 판정 → 확정)를 위한 거래 컬럼.
--
-- 이체·출금은 먼저 PENDING_REVIEW로 저장되고 금액이 홀드로 묶인 뒤, FDS 판정에 따라
-- COMPLETED(실행) 또는 HELD(보류)로 확정된다. status 컬럼에는 CHECK가 없으므로
-- 새 상태값(PENDING_REVIEW, EXPIRED)은 스키마 변경 없이 저장된다.

-- 거래 채널. 판정 요청과 이벤트에 실린다. 기존 거래는 모두 모바일로 본다.
ALTER TABLE transactions
    ADD COLUMN channel VARCHAR(10) NOT NULL DEFAULT 'MOBILE';
ALTER TABLE transactions
    ADD CONSTRAINT ck_transactions_channel CHECK (channel IN ('MOBILE', 'TELLER', 'ATM'));

-- 완료 시각. 보류됐다가 나중에 실행된 거래는 created_at(요청 시각)과 완료 시각이 다르다.
-- 1일 한도는 "그날 실제로 빠져나간 돈"을 세야 하므로 created_at이 아니라 이 컬럼으로 집계한다.
-- (created_at으로 세면 어제 보류되어 오늘 실행된 거래가 어느 날의 한도에도 잡히지 않는다.)
ALTER TABLE transactions
    ADD COLUMN completed_at TIMESTAMP;
UPDATE transactions
SET completed_at = created_at
WHERE status = 'COMPLETED';

-- 판정 결과. 판정 전(PENDING_REVIEW)이거나 판정 대상이 아닌 거래(입금)는 모두 NULL.
--   decision        : APPROVE / HOLD
--   decision_id     : FDS 서비스가 발급한 판정 식별자 (대체 정책이면 NULL)
--   risk_level      : NORMAL / CAUTION / DANGER / HIGH_RISK
--   reason_codes    : 사유 코드 목록, 쉼표 구분 (코드는 [A-Z0-9_]만 허용하므로 쉼표와 충돌하지 않는다)
--   decision_source : FDS(판정 서비스) / FALLBACK(판정 서비스 장애로 ibank 대체 정책) / LOCAL(판정 서비스 미연동 모드)
--   decided_at      : 판정을 반영한 시각
ALTER TABLE transactions
    ADD COLUMN decision        VARCHAR(10),
    ADD COLUMN decision_id     VARCHAR(64),
    ADD COLUMN risk_level      VARCHAR(20),
    ADD COLUMN reason_codes    VARCHAR(1100),
    ADD COLUMN decision_source VARCHAR(10),
    ADD COLUMN decided_at      TIMESTAMP;

-- FAILED 사유: 승인(APPROVE) 판정 뒤 실행 시점 재검증에 실패한 이유 코드.
ALTER TABLE transactions
    ADD COLUMN failure_code VARCHAR(50);

-- 1일 한도 집계: 계좌의 당일 완료 출금 합계. 완료 거래만 보므로 부분 인덱스.
CREATE INDEX idx_transaction_from_account_completed
    ON transactions (from_account_id, completed_at)
    WHERE status = 'COMPLETED';

-- 복구 배치: 판정 도중 프로세스가 죽어 PENDING_REVIEW로 남은 거래를 오래된 순으로 찾는다.
-- 정상 상황에서 PENDING_REVIEW 행은 극소수이므로 부분 인덱스가 작게 유지된다.
CREATE INDEX idx_transaction_pending_review
    ON transactions (created_at)
    WHERE status = 'PENDING_REVIEW';
