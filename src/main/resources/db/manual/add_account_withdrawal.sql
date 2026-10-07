-- PostgreSQL. 배포 전에 실행. 기존 사용자/클랜원/게임 결과 삭제 없음.
BEGIN;
ALTER TABLE users ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE users ADD COLUMN IF NOT EXISTS withdrawn_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE community_users ADD COLUMN IF NOT EXISTS ended_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE community_members ADD COLUMN IF NOT EXISTS anonymized BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE community_members ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE community_member_accounts ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE community_member_accounts ADD COLUMN IF NOT EXISTS anonymized BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE discord_voice_sessions ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'users'::regclass AND conname = 'ck_users_status') THEN
        ALTER TABLE users ADD CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'WITHDRAWN'));
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_community_users_active_user
    ON community_users(user_id, community_id) WHERE ended_at IS NULL;
-- Hibernate가 enum CHECK를 생성했거나 기존 수동 배포에 CHECK가 있는 경우 새 감사 코드를 허용한다.
-- 코드 목록을 하드코딩해 기존 이벤트를 제거하지 않고 기존 조건을 확장한다.
DO $$ DECLARE rule RECORD; BEGIN
    FOR rule IN SELECT conname, pg_get_expr(conbin, conrelid) AS expression
        FROM pg_constraint WHERE conrelid = 'monitoring_events'::regclass AND contype = 'c'
          AND pg_get_expr(conbin, conrelid) LIKE '%event_code%'
          AND pg_get_expr(conbin, conrelid) NOT LIKE '%USER_WITHDRAWN%'
    LOOP
        EXECUTE format('ALTER TABLE monitoring_events DROP CONSTRAINT %I', rule.conname);
        EXECUTE format('ALTER TABLE monitoring_events ADD CONSTRAINT %I CHECK ((%s) OR event_code = %L)',
            rule.conname, rule.expression, 'USER_WITHDRAWN');
    END LOOP;
END $$;
COMMIT;
