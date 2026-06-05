-- access 토큰 즉시 무효화용 토큰 버전.
-- access 토큰은 stateless JWT라 발급 후 만료까지 살아있다. 계정 도용 신고/재사용 탐지 시
-- 이 값을 증가시키면, 토큰에 박힌 버전과 불일치하여 기존 access 토큰이 즉시 거부된다.
ALTER TABLE users
    ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
