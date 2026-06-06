-- email PII at-rest 암호화 + blind index 도입.
-- email은 AES-GCM 암호문(base64)을 담으므로 길이를 늘리고, 유니크는 암호문(랜덤 IV로 매번 달라짐) 대신
-- 결정적 blind index(HMAC)로 강제한다.
-- 주의: 기존 평문 행은 앱 키가 필요한 재암호화 backfill 없이는 그대로 읽을 수 없다.
--       (이 포트폴리오는 신규 스키마 기준이며, 운영 적용 시 별도 데이터 마이그레이션이 필요하다.)
ALTER TABLE users DROP CONSTRAINT uq_users_email;
ALTER TABLE users ALTER COLUMN email TYPE VARCHAR(512);
ALTER TABLE users ADD COLUMN email_bidx VARCHAR(64) NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_email_bidx UNIQUE (email_bidx);
