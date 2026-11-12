-- 정기예금 계좌.
--
-- 계좌에 유형을 둔다. 기존 계좌는 모두 입출금 계좌(DEMAND)다.
--   DEMAND       : 입출금 계좌. 입금·출금·이체가 자유롭다.
--   TIME_DEPOSIT : 정기예금. 가입할 때 입출금 계좌에서 한 번 자금이 들어오고, 해지할 때 한 번 나간다.
--                  그 사이에는 직접 입금·출금·이체할 수 없다.
--
-- 잔액과 원장은 유형과 무관하게 같은 방식으로 관리한다(가입·해지 모두 합이 0인 분개).
-- 그래서 정산 배치는 계좌 유형을 따로 구분하지 않고 그대로 동작한다.
ALTER TABLE accounts
    ADD COLUMN account_type VARCHAR(20) NOT NULL DEFAULT 'DEMAND';
ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_account_type CHECK (account_type IN ('DEMAND', 'TIME_DEPOSIT'));

-- 정기예금 약정.
--   maturity_date : 만기일(영업 타임존 기준 날짜)
--   contract_rate : 약정 연이율 (0.035 = 3.5%). 가입 시점의 금리를 계좌에 고정한다 — 이후 금리 설정이 바뀌어도 약정은 그대로다.
--   paid_interest : 해지 때 지급한 이자. 해지 전에는 NULL.
ALTER TABLE accounts
    ADD COLUMN maturity_date DATE,
    ADD COLUMN contract_rate NUMERIC(7, 5),
    ADD COLUMN paid_interest NUMERIC(19, 2);

-- 정기예금이면 약정이 반드시 있고, 입출금 계좌면 없어야 한다.
ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_time_deposit_terms
        CHECK ((account_type = 'TIME_DEPOSIT') = (maturity_date IS NOT NULL AND contract_rate IS NOT NULL));
