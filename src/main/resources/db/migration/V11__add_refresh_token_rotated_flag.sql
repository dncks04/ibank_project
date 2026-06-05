-- 리프레시 토큰 재사용 탐지를 위한 회전(rotation) 표식.
-- 회전으로 폐기된 토큰(rotated=true)의 재제출은 탈취 의심으로 전체 세션을 무효화하고,
-- 로그아웃으로 폐기된 토큰(rotated=false)의 재제출은 단순 거부한다(다른 기기 세션은 유지).
ALTER TABLE refresh_tokens
    ADD COLUMN rotated BOOLEAN NOT NULL DEFAULT FALSE;
