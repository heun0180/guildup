-- GuildUp 서비스 전체 권한. 커뮤니티 OWNER/ADMIN 및 Discord 권한과 독립적이다.
-- 운영 PostgreSQL DB에 서버 배포 전에 한 번 적용한다.
BEGIN;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS system_role VARCHAR(20) NOT NULL DEFAULT 'USER';

UPDATE users
SET system_role = 'USER'
WHERE system_role IS NULL;

ALTER TABLE users
    DROP CONSTRAINT IF EXISTS ck_users_system_role;

ALTER TABLE users
    ADD CONSTRAINT ck_users_system_role
    CHECK (system_role IN ('USER', 'SYSTEM_ADMIN'));

COMMIT;

-- 권한 부여 예시(Discord ID로 대상을 명확히 확인한 뒤 실행):
-- UPDATE users
-- SET system_role = 'SYSTEM_ADMIN', updated_at = CURRENT_TIMESTAMP
-- WHERE id = (
--     SELECT user_id FROM user_external_accounts
--     WHERE provider = 'DISCORD' AND external_user_id = 'DISCORD_USER_ID'
-- );
