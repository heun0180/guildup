-- PostgreSQL. 운영 적용은 관리자가 배포 전에 수동 실행한다.
-- 로그인 제한은 메모리 저장소이므로 새 테이블/사용자 데이터 변경은 없다.
-- 기존 category/event_code CHECK가 있을 때 새 값만 추가 허용한다. 재실행 가능.
BEGIN;
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
          AND (pg_get_expr(conbin, conrelid) NOT LIKE '%LOGIN_REPEATED_FAILURE%'
            OR pg_get_expr(conbin, conrelid) NOT LIKE '%LOGIN_RATE_LIMITED%'
            OR pg_get_expr(conbin, conrelid) NOT LIKE '%LOGIN_IP_VOLUME%'
            OR pg_get_expr(conbin, conrelid) NOT LIKE '%LOGIN_MULTI_ACCOUNT%'
            OR pg_get_expr(conbin, conrelid) NOT LIKE '%LOGIN_ATTACK_SUSPECTED%')
    LOOP
        EXECUTE format('ALTER TABLE monitoring_events DROP CONSTRAINT %I', rule.conname);
        EXECUTE format('ALTER TABLE monitoring_events ADD CONSTRAINT %I CHECK ((%s) OR event_code IN (%L, %L, %L, %L, %L))',
            rule.conname, rule.expression, 'LOGIN_REPEATED_FAILURE', 'LOGIN_RATE_LIMITED', 'LOGIN_IP_VOLUME',
            'LOGIN_MULTI_ACCOUNT', 'LOGIN_ATTACK_SUSPECTED');
    END LOOP;
END $$;
COMMIT;
