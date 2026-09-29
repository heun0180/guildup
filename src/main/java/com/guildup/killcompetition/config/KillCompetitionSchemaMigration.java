package com.guildup.killcompetition.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 기존 PostgreSQL DB의 킬내기 상태 제약조건을 현재 상태 enum과 맞춘다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class KillCompetitionSchemaMigration implements ApplicationRunner {

    private static final String SCORE_COLUMNS_MIGRATION = """
            ALTER TABLE kill_competitions ADD COLUMN IF NOT EXISTS kill_point INTEGER NOT NULL DEFAULT 1;
            ALTER TABLE kill_competitions ADD COLUMN IF NOT EXISTS placement_point_enabled BOOLEAN NOT NULL DEFAULT FALSE;
            ALTER TABLE kill_competitions ADD COLUMN IF NOT EXISTS first_place_point INTEGER NOT NULL DEFAULT 5;
            ALTER TABLE kill_competitions ADD COLUMN IF NOT EXISTS second_place_point INTEGER NOT NULL DEFAULT 4;
            ALTER TABLE kill_competitions ADD COLUMN IF NOT EXISTS third_place_point INTEGER NOT NULL DEFAULT 3;
            ALTER TABLE kill_competitions ADD COLUMN IF NOT EXISTS fourth_fifth_place_point INTEGER NOT NULL DEFAULT 2;
            ALTER TABLE kill_competitions ADD COLUMN IF NOT EXISTS sixth_tenth_place_point INTEGER NOT NULL DEFAULT 1;
            ALTER TABLE kill_competitions ADD COLUMN IF NOT EXISTS fourth_place_point INTEGER;
            ALTER TABLE kill_competitions ADD COLUMN IF NOT EXISTS fifth_place_point INTEGER;
            ALTER TABLE kill_competitions ADD COLUMN IF NOT EXISTS sixth_place_point INTEGER;
            ALTER TABLE kill_competitions ADD COLUMN IF NOT EXISTS seventh_place_point INTEGER;
            ALTER TABLE kill_competitions ADD COLUMN IF NOT EXISTS eighth_place_point INTEGER;
            ALTER TABLE kill_competitions ADD COLUMN IF NOT EXISTS ninth_place_point INTEGER;
            ALTER TABLE kill_competitions ADD COLUMN IF NOT EXISTS tenth_place_point INTEGER;
            ALTER TABLE kill_competition_participants ADD COLUMN IF NOT EXISTS interim_points INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE kill_competition_participants ADD COLUMN IF NOT EXISTS final_points INTEGER;
            ALTER TABLE kill_competition_match_results ADD COLUMN IF NOT EXISTS placement INTEGER;
            ALTER TABLE kill_competition_match_results ADD COLUMN IF NOT EXISTS kill_points INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE kill_competition_match_results ADD COLUMN IF NOT EXISTS placement_points INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE kill_competition_match_results ADD COLUMN IF NOT EXISTS total_points INTEGER NOT NULL DEFAULT 0;
            UPDATE kill_competition_participants participant
            SET interim_points = participant.interim_kills * competition.kill_point
            FROM kill_competitions competition
            WHERE participant.competition_id = competition.id AND participant.interim_points = 0
              AND participant.interim_kills <> 0;
            UPDATE kill_competition_participants participant
            SET final_points = participant.final_kills * competition.kill_point
            FROM kill_competitions competition
            WHERE participant.competition_id = competition.id AND participant.final_kills IS NOT NULL
              AND participant.final_points IS NULL;
            UPDATE kill_competition_match_results result
            SET kill_points = result.kills, placement_points = 0, total_points = result.kills
            FROM kill_competitions competition
            WHERE result.competition_id = competition.id
              AND competition.kill_point = 1 AND competition.placement_point_enabled = FALSE
              AND result.placement IS NULL AND result.kill_points = 0
              AND result.placement_points = 0 AND result.total_points = 0;
            """;

    private static final String REQUIRES_STATUS_CONSTRAINT_MIGRATION = """
            SELECT NOT EXISTS (
                       SELECT 1
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
                   )
                OR EXISTS (
                       SELECT 1
                       FROM pg_constraint constraint_info
                       JOIN pg_class table_info ON table_info.oid = constraint_info.conrelid
                       JOIN pg_namespace schema_info ON schema_info.oid = table_info.relnamespace
                       WHERE schema_info.nspname = current_schema()
                         AND table_info.relname = 'kill_competitions'
                         AND constraint_info.contype = 'c'
                         AND position('RESULT_PENDING' in pg_get_constraintdef(constraint_info.oid)) = 0
                         AND EXISTS (
                             SELECT 1
                             FROM unnest(constraint_info.conkey) AS constraint_column(attnum)
                             JOIN pg_attribute column_info
                               ON column_info.attrelid = constraint_info.conrelid
                              AND column_info.attnum = constraint_column.attnum
                             WHERE column_info.attname = 'status'
                         )
                   )
            """;

    private static final String DROP_STATUS_CONSTRAINTS = """
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
            $migration$
            """;

    private static final String ADD_STATUS_CONSTRAINT = """
            ALTER TABLE kill_competitions
            ADD CONSTRAINT ck_kill_competition_status
            CHECK (status IN (
                'RECRUITING',
                'READY',
                'IN_PROGRESS',
                'RESULT_PENDING',
                'COMPLETED',
                'CANCELLED'
            ))
            """;

    private final JdbcTemplate jdbcTemplate;

    public KillCompetitionSchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!isPostgreSql()) {
            return;
        }

        jdbcTemplate.execute(SCORE_COLUMNS_MIGRATION);

        Boolean migrationRequired = jdbcTemplate.queryForObject(
                REQUIRES_STATUS_CONSTRAINT_MIGRATION,
                Boolean.class
        );
        if (!Boolean.TRUE.equals(migrationRequired)) {
            return;
        }

        jdbcTemplate.execute(DROP_STATUS_CONSTRAINTS);
        jdbcTemplate.execute(ADD_STATUS_CONSTRAINT);
    }

    private boolean isPostgreSql() {
        String productName = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection -> connection.getMetaData().getDatabaseProductName()
        );
        return "PostgreSQL".equalsIgnoreCase(productName);
    }
}
