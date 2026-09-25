-- RESULT_PENDING 도입 전에 생성된 PostgreSQL DB의 상태 체크 제약조건을 갱신한다.
-- Hibernate ddl-auto=update는 기존 CHECK 제약조건의 허용값을 변경하지 않는다.

DO $migration$
DECLARE
    status_constraint RECORD;
BEGIN
    FOR status_constraint IN
        SELECT constraint_info.conname
        FROM pg_constraint constraint_info
        JOIN pg_class table_info ON table_info.oid = constraint_info.conrelid
        JOIN pg_namespace schema_info ON schema_info.oid = table_info.relnamespace
        WHERE schema_info.nspname = current_schema()
          AND table_info.relname = 'kill_competitions'
          AND constraint_info.contype = 'c'
          AND EXISTS (
              SELECT 1
              FROM unnest(constraint_info.conkey) AS constraint_column(attnum)
              JOIN pg_attribute column_info
                ON column_info.attrelid = constraint_info.conrelid
               AND column_info.attnum = constraint_column.attnum
              WHERE column_info.attname = 'status'
          )
    LOOP
        EXECUTE format(
            'ALTER TABLE kill_competitions DROP CONSTRAINT %I',
            status_constraint.conname
        );
    END LOOP;
END
$migration$;

ALTER TABLE kill_competitions
ADD CONSTRAINT ck_kill_competition_status
CHECK (status IN (
    'RECRUITING',
    'READY',
    'IN_PROGRESS',
    'RESULT_PENDING',
    'COMPLETED',
    'CANCELLED'
));
