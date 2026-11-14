-- 정산 배치의 "시스템 전체 검증" 결과.
--
-- 기존 reconciliation_results는 계좌 한 개당 한 행(잔액 vs 원장 합계)이다. 홀드와 거래 상태의 짝처럼
-- 계좌 단위가 아닌 검증은 담을 자리가 없어, 검증 항목 한 개당 한 행으로 따로 남긴다.
-- 실행마다(job_execution_id) 모든 항목이 기록되므로, 위반이 없었다는 사실도 이력으로 남는다.
--   check_name : 검증 항목
--                  UNBALANCED_JOURNALS                : 합이 0이 아닌 분개
--                  TRIAL_BALANCE                      : 전 원장 시산표가 0이 아님
--                  HOLD_SUM_EXCEEDS_BALANCE           : ACTIVE 홀드 합계가 원장잔액을 넘는 계좌
--                  ACTIVE_HOLD_WITHOUT_OPEN_TRANSACTION : 판정 대기·보류가 아닌 거래에 걸린 ACTIVE 홀드
--                  OPEN_TRANSACTION_WITHOUT_ACTIVE_HOLD : ACTIVE 홀드가 없는 판정 대기·보류 거래
--                  UNEXECUTED_TRANSACTION_WITH_LEDGER : 실행되지 않은 거래(취소·만료·실패)에 달린 분개
--   violations : 위반 건수 (0이면 정상)
--   sample     : 위반 대상의 식별자 일부(최대 20개). 원인을 찾을 때의 출발점
CREATE TABLE reconciliation_check_results
(
    id               BIGSERIAL   NOT NULL,
    job_execution_id BIGINT      NOT NULL,
    check_name       VARCHAR(50) NOT NULL,
    violations       BIGINT      NOT NULL,
    consistent       BOOLEAN     NOT NULL,
    sample           VARCHAR(500),
    created_at       TIMESTAMP   NOT NULL,

    CONSTRAINT pk_reconciliation_check_results PRIMARY KEY (id),
    CONSTRAINT uq_reconciliation_check_results UNIQUE (job_execution_id, check_name),
    CONSTRAINT ck_reconciliation_check_results CHECK (consistent = (violations = 0))
);
