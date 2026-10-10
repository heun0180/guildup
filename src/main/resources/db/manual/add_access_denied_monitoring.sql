-- PostgreSQL VARCHAR + CHECK schema only. Prepared for operator review; never applied automatically.
-- Preserve existing constraints/rows and allow the new diagnostic event code. No account data changes.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
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
          AND pg_get_expr(conbin, conrelid) NOT LIKE '%HTTP_ACCESS_DENIED%'
    LOOP
        EXECUTE format('ALTER TABLE monitoring_events DROP CONSTRAINT %I', rule.conname);
        EXECUTE format('ALTER TABLE monitoring_events ADD CONSTRAINT %I CHECK ((%s) OR event_code = %L)',
            rule.conname, rule.expression, 'HTTP_ACCESS_DENIED');
    END LOOP;
END $$;
COMMIT;
