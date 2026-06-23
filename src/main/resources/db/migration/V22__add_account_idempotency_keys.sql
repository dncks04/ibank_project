-- 계좌 개설/해지 멱등성: 네트워크 재시도로 인한 계좌 중복 생성·중복 해지를 방지한다.
--
-- open_idempotency_key  : 개설 요청의 멱등성 키. 같은 키 재요청은 기존 계좌를 replay한다.
--                         중복 생성 차단을 위해 UNIQUE. 기존 행은 키가 없으므로 nullable
--                         (UNIQUE는 다중 NULL을 허용하며, 이후 개설되는 계좌부터 항상 기록).
-- close_idempotency_key : 해지 요청의 멱등성 키. 같은 키로 이미 해지된 계좌면 재요청을
--                         성공으로 흡수한다(no-op). 한 계좌 범위에서만 비교하므로 UNIQUE 불필요.
ALTER TABLE accounts
    ADD COLUMN open_idempotency_key  VARCHAR(64),
    ADD COLUMN close_idempotency_key VARCHAR(64);

ALTER TABLE accounts
    ADD CONSTRAINT uq_accounts_open_idempotency_key UNIQUE (open_idempotency_key);
