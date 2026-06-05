-- 동일 계좌 이체 차단의 DB 차원 최후 방어선.
-- 이체(TRANSFER)는 from/to가 모두 채워지므로 둘이 같으면 거부한다.
-- 입금(DEPOSIT, from=NULL)·출금(WITHDRAW, to=NULL)은 한쪽이 NULL이라 제약에 걸리지 않는다.
ALTER TABLE transactions
    ADD CONSTRAINT ck_transactions_distinct_accounts
        CHECK (from_account_id IS NULL OR to_account_id IS NULL OR from_account_id <> to_account_id);
