-- 멱등성 키 재사용 방어: 동일 키 + 다른 요청 내용을 구분하기 위한 요청 지문(fingerprint).
-- 같은 idempotency_key로 다른 금액/계좌 요청이 오면 옛 결과를 조용히 replay하지 않고 409로 거부한다.
-- SHA-256 16진수 표현이므로 64자 고정.
-- 기존 행은 지문이 없으므로 nullable(이후 생성되는 거래부터 항상 기록).
ALTER TABLE transactions
    ADD COLUMN request_fingerprint VARCHAR(64);
