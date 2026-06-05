-- 감사 로그. 누가(actor)·언제(created_at)·무엇을(action/target)·결과(result)·어디서(ip).
-- 민감 정보(잔액·원시 오류 등)는 기록하지 않는다.
CREATE TABLE audit_logs
(
    id         BIGSERIAL    NOT NULL,
    actor      VARCHAR(50)  NOT NULL,   -- loginId 또는 ANONYMOUS
    action     VARCHAR(100) NOT NULL,   -- ACCOUNT_OPEN, TRANSFER, ...
    target     VARCHAR(100),            -- 대상 식별자(예: 계좌번호)
    result     VARCHAR(20)  NOT NULL,   -- SUCCESS | FAILURE
    detail     VARCHAR(500),            -- 부가 정보(예외 타입 등, 비민감)
    ip         VARCHAR(45),
    created_at TIMESTAMP    NOT NULL,

    CONSTRAINT pk_audit_logs PRIMARY KEY (id)
);

CREATE INDEX idx_audit_logs_actor ON audit_logs (actor);
CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at);
