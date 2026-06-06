-- 회전 시각 기록. 회전 직후 짧은 유예창(leeway) 안의 재제출은 정상 동시 재시도(재시도/더블클릭)로 보고
-- 전체 세션 무효화 없이 단순 거부한다. 유예창 밖 재제출만 탈취로 간주한다.
-- 기존 회전된 토큰(rotated_at IS NULL)은 유예창에 들지 않으므로 종전대로 재사용 탐지된다.
ALTER TABLE refresh_tokens
    ADD COLUMN rotated_at TIMESTAMP NULL;
