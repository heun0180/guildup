-- PostgreSQL. 관리자가 배포 전에 수동 적용한다. 운영 DB에 자동 실행하지 않는다.
-- 선행: add_user_credentials.sql, add_monitoring_events.sql, add_account_withdrawal.sql
-- 기존 ID/로그인 정보/Discord 연결/커뮤니티/게임 기록 및 email_verified는 변경하지 않는다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
ALTER TABLE user_credentials ADD COLUMN IF NOT EXISTS verification_required BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS email_verification_tokens (
    id BIGSERIAL PRIMARY KEY,
    credential_id BIGINT NOT NULL REFERENCES user_credentials(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    delivery VARCHAR(16) NOT NULL,
    CONSTRAINT ck_email_verification_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_email_verification_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_email_verification_delivery CHECK (delivery IN ('QUEUED', 'SENDING', 'SENT', 'FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_email_verification_credential_created ON email_verification_tokens(credential_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_email_verification_expires ON email_verification_tokens(expires_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_email_verification_active_credential
    ON email_verification_tokens(credential_id) WHERE used_at IS NULL AND invalidated_at IS NULL;

-- Hibernate 또는 과거 수동 SQL의 enum CHECK를 기존 조건을 보존하며 확장한다.
DO $$ DECLARE rule RECORD; BEGIN
    FOR rule IN SELECT conname, pg_get_expr(conbin, conrelid) AS expression
        FROM pg_constraint WHERE conrelid = 'monitoring_events'::regclass AND contype = 'c'
          AND pg_get_expr(conbin, conrelid) LIKE '%category%'
          AND pg_get_expr(conbin, conrelid) NOT LIKE '%SECURITY%'
    LOOP
        EXECUTE format('ALTER TABLE monitoring_events DROP CONSTRAINT %I', rule.conname);
        EXECUTE format('ALTER TABLE monitoring_events ADD CONSTRAINT %I CHECK ((%s) OR category = %L)',
            rule.conname, rule.expression, 'SECURITY');
    END LOOP;
    FOR rule IN SELECT conname, pg_get_expr(conbin, conrelid) AS expression
        FROM pg_constraint WHERE conrelid = 'monitoring_events'::regclass AND contype = 'c'
          AND pg_get_expr(conbin, conrelid) LIKE '%event_code%'
          AND (pg_get_expr(conbin, conrelid) NOT LIKE '%EMAIL_VERIFICATION_MAIL_FAILED%'
            OR pg_get_expr(conbin, conrelid) NOT LIKE '%EMAIL_VERIFICATION_INVALID%'
            OR pg_get_expr(conbin, conrelid) NOT LIKE '%EMAIL_VERIFICATION_EXPIRED%'
            OR pg_get_expr(conbin, conrelid) NOT LIKE '%EMAIL_VERIFICATION_RATE_LIMITED%'
            OR pg_get_expr(conbin, conrelid) NOT LIKE '%EMAIL_VERIFICATION_ABUSE%'
            OR pg_get_expr(conbin, conrelid) NOT LIKE '%EMAIL_VERIFICATION_COMPLETED%')
    LOOP
        EXECUTE format('ALTER TABLE monitoring_events DROP CONSTRAINT %I', rule.conname);
        EXECUTE format('ALTER TABLE monitoring_events ADD CONSTRAINT %I CHECK ((%s) OR event_code IN (%L,%L,%L,%L,%L,%L))',
            rule.conname, rule.expression, 'EMAIL_VERIFICATION_MAIL_FAILED', 'EMAIL_VERIFICATION_INVALID',
            'EMAIL_VERIFICATION_EXPIRED', 'EMAIL_VERIFICATION_RATE_LIMITED', 'EMAIL_VERIFICATION_ABUSE', 'EMAIL_VERIFICATION_COMPLETED');
    END LOOP;
END $$;
COMMIT;
