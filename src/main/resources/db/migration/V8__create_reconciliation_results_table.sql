-- 정산 배치 결과. 각 실행에서 계좌별 잔액 vs 원장 합계 일치 여부를 스냅샷으로 남긴다.
-- 이력성 데이터이므로 accounts에 FK를 두지 않는다(계좌가 삭제돼도 결과는 보존).
CREATE TABLE reconciliation_results
(
    id               BIGSERIAL      NOT NULL,
    job_execution_id BIGINT,
    account_id       BIGINT         NOT NULL,
    balance          NUMERIC(19, 2) NOT NULL,
    ledger_sum       NUMERIC(19, 2) NOT NULL,
    consistent       BOOLEAN        NOT NULL,
    created_at       TIMESTAMP      NOT NULL,

    CONSTRAINT pk_reconciliation_results PRIMARY KEY (id)
);

CREATE INDEX idx_reconciliation_results_job ON reconciliation_results (job_execution_id);
CREATE INDEX idx_reconciliation_results_consistent ON reconciliation_results (consistent);
