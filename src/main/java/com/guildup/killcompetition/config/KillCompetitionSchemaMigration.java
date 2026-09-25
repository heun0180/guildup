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
