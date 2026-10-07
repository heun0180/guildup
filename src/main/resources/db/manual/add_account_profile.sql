-- PostgreSQL. 애플리케이션 배포 전에 운영자가 별도로 실행한다.
-- 기존 닉네임/사용자/외부 계정은 보존하며 추가 정보는 NULL로 시작한다.
BEGIN;
ALTER TABLE users ADD COLUMN IF NOT EXISTS birth_date DATE;
ALTER TABLE user_external_accounts ADD COLUMN IF NOT EXISTS external_display_name VARCHAR(255);
ALTER TABLE user_external_accounts ADD COLUMN IF NOT EXISTS external_avatar_url VARCHAR(512);
COMMIT;
