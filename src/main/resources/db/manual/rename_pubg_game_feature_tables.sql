-- Bingo와 킬내기 물리 테이블을 PUBG namespace로 이동한다.
-- 테이블과 identity sequence를 새로 만들지 않고 같은 객체를 rename하므로 데이터와 FK 연결이 유지된다.
-- 대상 이름이 이미 다른 객체에 사용 중이면 자동 병합하지 않고 즉시 실패한다.

BEGIN;

DO $migration$
DECLARE
    mapping RECORD;
    old_oid OID;
    new_oid OID;
BEGIN
    FOR mapping IN
        SELECT *
        FROM (VALUES
            ('bingo_events', 'pubg_bingo_events'),
            ('bingo_cells', 'pubg_bingo_cells'),
            ('bingo_participants', 'pubg_bingo_participants'),
            ('bingo_progress', 'pubg_bingo_progress'),
            ('bingo_line_completions', 'pubg_bingo_line_completions'),
            ('bingo_processed_matches', 'pubg_bingo_processed_matches'),
            ('bingo_processed_sources', 'pubg_bingo_processed_sources'),
            ('kill_competitions', 'pubg_kill_competitions'),
            ('kill_competition_participants', 'pubg_kill_competition_participants'),
            ('kill_competition_teams', 'pubg_kill_competition_teams'),
            ('kill_competition_match_results', 'pubg_kill_competition_match_results')
        ) AS names(old_name, new_name)
    LOOP
        old_oid := to_regclass(format('%I.%I', current_schema(), mapping.old_name));
        new_oid := to_regclass(format('%I.%I', current_schema(), mapping.new_name));

        IF old_oid IS NOT NULL AND new_oid IS NOT NULL THEN
            RAISE EXCEPTION 'Both %.% and %.% exist; refusing to merge tables',
                current_schema(), mapping.old_name, current_schema(), mapping.new_name;
        ELSIF old_oid IS NOT NULL THEN
            EXECUTE format('ALTER TABLE %I.%I RENAME TO %I',
                current_schema(), mapping.old_name, mapping.new_name);
            IF to_regclass(format('%I.%I', current_schema(), mapping.new_name)) <> old_oid THEN
                RAISE EXCEPTION 'Table OID changed while renaming % to %', mapping.old_name, mapping.new_name;
            END IF;
        ELSIF new_oid IS NULL THEN
            RAISE EXCEPTION 'Neither %.% nor %.% exists',
                current_schema(), mapping.old_name, current_schema(), mapping.new_name;
        END IF;
    END LOOP;

    FOR mapping IN
        SELECT *
        FROM (VALUES
            ('bingo_events_id_seq', 'pubg_bingo_events_id_seq'),
            ('bingo_cells_id_seq', 'pubg_bingo_cells_id_seq'),
            ('bingo_participants_id_seq', 'pubg_bingo_participants_id_seq'),
            ('bingo_progress_id_seq', 'pubg_bingo_progress_id_seq'),
            ('bingo_line_completions_id_seq', 'pubg_bingo_line_completions_id_seq'),
            ('bingo_processed_matches_id_seq', 'pubg_bingo_processed_matches_id_seq'),
            ('bingo_processed_sources_id_seq', 'pubg_bingo_processed_sources_id_seq'),
            ('kill_competitions_id_seq', 'pubg_kill_competitions_id_seq'),
            ('kill_competition_participants_id_seq', 'pubg_kill_competition_participants_id_seq'),
            ('kill_competition_teams_id_seq', 'pubg_kill_competition_teams_id_seq'),
            ('kill_competition_match_results_id_seq', 'pubg_kill_competition_match_results_id_seq')
        ) AS names(old_name, new_name)
    LOOP
        old_oid := to_regclass(format('%I.%I', current_schema(), mapping.old_name));
        new_oid := to_regclass(format('%I.%I', current_schema(), mapping.new_name));

        IF old_oid IS NOT NULL AND new_oid IS NOT NULL THEN
            RAISE EXCEPTION 'Both %.% and %.% exist; refusing to merge sequences',
                current_schema(), mapping.old_name, current_schema(), mapping.new_name;
        ELSIF old_oid IS NOT NULL THEN
            EXECUTE format('ALTER SEQUENCE %I.%I RENAME TO %I',
                current_schema(), mapping.old_name, mapping.new_name);
            IF to_regclass(format('%I.%I', current_schema(), mapping.new_name)) <> old_oid THEN
                RAISE EXCEPTION 'Sequence OID changed while renaming % to %', mapping.old_name, mapping.new_name;
            END IF;
        ELSIF new_oid IS NULL THEN
            RAISE EXCEPTION 'Neither %.% nor %.% exists',
                current_schema(), mapping.old_name, current_schema(), mapping.new_name;
        END IF;
    END LOOP;
END
$migration$;

-- 사람이 스키마를 읽을 때도 PUBG 전용 객체임을 알 수 있도록 기존 명칭이 포함된
-- PK/UK/CHECK constraint와 명시적 index 이름도 함께 정리한다. 해시형 FK 이름은 유지한다.
DO $migration$
DECLARE
    object_info RECORD;
    renamed_name TEXT;
BEGIN
    FOR object_info IN
        SELECT table_info.relname AS table_name, constraint_info.conname AS object_name
        FROM pg_constraint constraint_info
        JOIN pg_class table_info ON table_info.oid = constraint_info.conrelid
        JOIN pg_namespace schema_info ON schema_info.oid = table_info.relnamespace
        WHERE schema_info.nspname = current_schema()
          AND (table_info.relname LIKE 'pubg_bingo_%' OR table_info.relname LIKE 'pubg_kill_competition%')
          AND (constraint_info.conname LIKE '%bingo%' OR constraint_info.conname LIKE '%kill_competition%')
          AND constraint_info.conname NOT LIKE '%pubg_bingo%'
          AND constraint_info.conname NOT LIKE '%pubg_kill_competition%'
    LOOP
        renamed_name := replace(replace(object_info.object_name,
            'bingo', 'pubg_bingo'), 'kill_competition', 'pubg_kill_competition');
        IF EXISTS (
            SELECT 1 FROM pg_constraint
            WHERE conrelid = format('%I.%I', current_schema(), object_info.table_name)::regclass
              AND conname = renamed_name
        ) THEN
            RAISE EXCEPTION 'Constraint % already exists on %', renamed_name, object_info.table_name;
        END IF;
        EXECUTE format('ALTER TABLE %I.%I RENAME CONSTRAINT %I TO %I',
            current_schema(), object_info.table_name, object_info.object_name, renamed_name);
    END LOOP;

    FOR object_info IN
        SELECT index_info.relname AS object_name
        FROM pg_class index_info
        JOIN pg_namespace schema_info ON schema_info.oid = index_info.relnamespace
        JOIN pg_index index_metadata ON index_metadata.indexrelid = index_info.oid
        JOIN pg_class table_info ON table_info.oid = index_metadata.indrelid
        WHERE schema_info.nspname = current_schema()
          AND (table_info.relname LIKE 'pubg_bingo_%' OR table_info.relname LIKE 'pubg_kill_competition%')
          AND (index_info.relname LIKE '%bingo%' OR index_info.relname LIKE '%kill_competition%')
          AND index_info.relname NOT LIKE '%pubg_bingo%'
          AND index_info.relname NOT LIKE '%pubg_kill_competition%'
    LOOP
        renamed_name := replace(replace(object_info.object_name,
            'bingo', 'pubg_bingo'), 'kill_competition', 'pubg_kill_competition');
        IF to_regclass(format('%I.%I', current_schema(), renamed_name)) IS NOT NULL THEN
            RAISE EXCEPTION 'Index %.% already exists', current_schema(), renamed_name;
        END IF;
        EXECUTE format('ALTER INDEX %I.%I RENAME TO %I',
            current_schema(), object_info.object_name, renamed_name);
    END LOOP;
END
$migration$;

-- 모든 id 컬럼은 identity다. pg_get_serial_sequence는 identity에도 적용되므로,
-- rename 후 각 컬럼이 새 이름의 기존 sequence를 계속 참조하는지 검증한다.
DO $migration$
DECLARE
    mapping RECORD;
    linked_sequence TEXT;
    expected_sequence TEXT;
BEGIN
    FOR mapping IN
        SELECT *
        FROM (VALUES
            ('pubg_bingo_events', 'pubg_bingo_events_id_seq'),
            ('pubg_bingo_cells', 'pubg_bingo_cells_id_seq'),
            ('pubg_bingo_participants', 'pubg_bingo_participants_id_seq'),
            ('pubg_bingo_progress', 'pubg_bingo_progress_id_seq'),
            ('pubg_bingo_line_completions', 'pubg_bingo_line_completions_id_seq'),
            ('pubg_bingo_processed_matches', 'pubg_bingo_processed_matches_id_seq'),
            ('pubg_bingo_processed_sources', 'pubg_bingo_processed_sources_id_seq'),
            ('pubg_kill_competitions', 'pubg_kill_competitions_id_seq'),
            ('pubg_kill_competition_participants', 'pubg_kill_competition_participants_id_seq'),
            ('pubg_kill_competition_teams', 'pubg_kill_competition_teams_id_seq'),
            ('pubg_kill_competition_match_results', 'pubg_kill_competition_match_results_id_seq')
        ) AS names(table_name, sequence_name)
    LOOP
        linked_sequence := pg_get_serial_sequence(
            format('%I.%I', current_schema(), mapping.table_name), 'id');
        expected_sequence := format('%I.%I', current_schema(), mapping.sequence_name);
        IF linked_sequence IS DISTINCT FROM expected_sequence THEN
            RAISE EXCEPTION 'Identity sequence mismatch for %: expected %, found %',
                mapping.table_name, expected_sequence, linked_sequence;
        END IF;
    END LOOP;
END
$migration$;

COMMIT;
