-- PostgreSQL. 관리자 수동 적용용. 선행: add_email_verification.sql 및 그 선행 마이그레이션.
-- 기존 사용자 ID/인증 상태/비밀번호/Discord 연결/커뮤니티 기록은 변경하지 않는다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
ALTER TABLE users ADD COLUMN IF NOT EXISTS authentication_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE email_verification_tokens ADD COLUMN IF NOT EXISTS purpose VARCHAR(32) NOT NULL DEFAULT 'EMAIL_VERIFICATION';
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'email_verification_tokens'::regclass AND conname = 'ck_email_verification_purpose') THEN
        ALTER TABLE email_verification_tokens ADD CONSTRAINT ck_email_verification_purpose CHECK (purpose = 'EMAIL_VERIFICATION');
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id BIGSERIAL PRIMARY KEY,
    credential_id BIGINT NOT NULL REFERENCES user_credentials(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    purpose VARCHAR(32) NOT NULL,
    authentication_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    delivery VARCHAR(16) NOT NULL,
    CONSTRAINT ck_password_reset_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_password_reset_purpose CHECK (purpose = 'PASSWORD_RESET'),
    CONSTRAINT ck_password_reset_version CHECK (authentication_version >= 0),
    CONSTRAINT ck_password_reset_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_password_reset_delivery CHECK (delivery IN ('QUEUED','SENDING','SENT','FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_password_reset_credential_created ON password_reset_tokens(credential_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_password_reset_expires ON password_reset_tokens(expires_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_password_reset_active_credential ON password_reset_tokens(credential_id)
    WHERE used_at IS NULL AND invalidated_at IS NULL;

CREATE TABLE IF NOT EXISTS password_reset_mail_quota (
    id BIGINT PRIMARY KEY CHECK (id = 1),
    budget_day DATE NOT NULL,
    budget_month DATE NOT NULL,
    daily_count INTEGER NOT NULL DEFAULT 0 CHECK (daily_count >= 0),
    monthly_count INTEGER NOT NULL DEFAULT 0 CHECK (monthly_count >= 0)
);
-- ddl-auto=update가 이미 생성한 테이블에도 동일한 제약을 보장한다.
DO $$ DECLARE rule RECORD; BEGIN
    FOR rule IN SELECT * FROM (VALUES
        ('users', 'ck_user_authentication_version', 'authentication_version >= 0'),
        ('password_reset_tokens', 'ck_password_reset_hash', 'token_hash ~ ''^[0-9a-f]{64}$'''),
        ('password_reset_tokens', 'ck_password_reset_purpose', 'purpose = ''PASSWORD_RESET'''),
        ('password_reset_tokens', 'ck_password_reset_version', 'authentication_version >= 0'),
        ('password_reset_tokens', 'ck_password_reset_expiry', 'expires_at > created_at'),
        ('password_reset_tokens', 'ck_password_reset_delivery', 'delivery IN (''QUEUED'',''SENDING'',''SENT'',''FAILED'')'),
        ('password_reset_mail_quota', 'ck_password_reset_quota_singleton', 'id = 1'),
        ('password_reset_mail_quota', 'ck_password_reset_quota_daily', 'daily_count >= 0'),
        ('password_reset_mail_quota', 'ck_password_reset_quota_monthly', 'monthly_count >= 0')
    ) AS checks(table_name, constraint_name, expression)
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = rule.table_name::regclass AND conname = rule.constraint_name) THEN
            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (%s)', rule.table_name, rule.constraint_name, rule.expression);
        END IF;
    END LOOP;
END $$;
INSERT INTO password_reset_mail_quota (id, budget_day, budget_month, daily_count, monthly_count)
VALUES (1, (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date,
        date_trunc('month', CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date, 0, 0)
ON CONFLICT (id) DO NOTHING;

-- 기존 monitoring enum CHECK를 보존하며 새 보안 이벤트만 허용한다.
DO $$ DECLARE rule RECORD; BEGIN
    FOR rule IN SELECT conname, pg_get_expr(conbin, conrelid) AS expression
        FROM pg_constraint WHERE conrelid = 'monitoring_events'::regclass AND contype = 'c'
          AND pg_get_expr(conbin, conrelid) LIKE '%event_code%'
          AND (pg_get_expr(conbin, conrelid) NOT LIKE '%PASSWORD_RESET_MAIL_FAILED%'
            OR pg_get_expr(conbin, conrelid) NOT LIKE '%PASSWORD_RESET_RATE_LIMITED%'
            OR pg_get_expr(conbin, conrelid) NOT LIKE '%PASSWORD_RESET_INVALID%'
            OR pg_get_expr(conbin, conrelid) NOT LIKE '%PASSWORD_RESET_EXPIRED%'
            OR pg_get_expr(conbin, conrelid) NOT LIKE '%PASSWORD_RESET_REUSED%'
            OR pg_get_expr(conbin, conrelid) NOT LIKE '%PASSWORD_RESET_ABUSE%'
            OR pg_get_expr(conbin, conrelid) NOT LIKE '%PASSWORD_RESET_COMPLETED%'
            OR pg_get_expr(conbin, conrelid) NOT LIKE '%PASSWORD_RESET_STORAGE_FAILED%')
    LOOP
        EXECUTE format('ALTER TABLE monitoring_events DROP CONSTRAINT %I', rule.conname);
        EXECUTE format('ALTER TABLE monitoring_events ADD CONSTRAINT %I CHECK ((%s) OR event_code IN (%L,%L,%L,%L,%L,%L,%L,%L))',
            rule.conname, rule.expression, 'PASSWORD_RESET_MAIL_FAILED', 'PASSWORD_RESET_RATE_LIMITED',
            'PASSWORD_RESET_INVALID', 'PASSWORD_RESET_EXPIRED', 'PASSWORD_RESET_REUSED', 'PASSWORD_RESET_ABUSE',
            'PASSWORD_RESET_COMPLETED', 'PASSWORD_RESET_STORAGE_FAILED');
    END LOOP;
END $$;
COMMIT;
