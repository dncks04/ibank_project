-- 감사 로그에 요청 상관관계 ID(request_id)를 기록한다.
-- 애플리케이션 로그(MDC %X{requestId})와 감사 기록을 같은 ID로 연결해
-- 하나의 요청을 끝까지 추적할 수 있게 한다. 과거 행은 NULL 허용.
ALTER TABLE audit_logs
    ADD COLUMN request_id VARCHAR(64);

-- 장애 추적 시 "이 요청에서 무슨 일이 있었나"를 ID 한 건으로 조회하는 용도
CREATE INDEX idx_audit_logs_request_id ON audit_logs (request_id);
