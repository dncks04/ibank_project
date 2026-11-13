-- 합성 데이터 대량 적재에서 적재하지 못한 행의 기록.
--
-- 적재는 한 행의 문제(형식 오류, 없는 계좌, 잔액 부족 등)로 전체를 멈추지 않고, 그 행만 건너뛰고 여기에 남긴다.
-- 적재가 끝난 뒤 이 테이블을 보고 원본 데이터를 고쳐 다시 적재한다(이미 적재된 행은 다시 적재되지 않는다).
--   step        : customers / accounts / transactions
--   line_number : 입력 파일의 줄 번호(1부터)
--   raw_line    : 문제가 된 줄 원문(앞 2,000자)
--   reason      : 건너뛴 이유
CREATE TABLE bulk_load_errors
(
    id               BIGSERIAL     NOT NULL,
    job_execution_id BIGINT        NOT NULL,
    step             VARCHAR(20)   NOT NULL,
    line_number      INT           NOT NULL,
    raw_line         VARCHAR(2000) NOT NULL,
    reason           VARCHAR(500)  NOT NULL,
    created_at       TIMESTAMP     NOT NULL,

    CONSTRAINT pk_bulk_load_errors PRIMARY KEY (id)
);

CREATE INDEX idx_bulk_load_errors_job ON bulk_load_errors (job_execution_id, step, line_number);
