package com.guildup.developer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class AdminUserMigrationPostgresTests {
    @Test void migrationIsIdempotentKeepsAccountsAndLeavesLegacyTimestampsNull() throws Exception {
        String schema = "admin_user_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("TEST_POSTGRES_USER", "postgres"), System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
             var sql = connection.createStatement()) {
            sql.execute("create schema " + schema);
            try {
                sql.execute("set search_path to " + schema);
                sql.execute("""
                        create table users(id bigint primary key, nickname text, status text, created_at timestamptz);
                        create table user_credentials(id bigint primary key, user_id bigint references users(id), email text, password_hash text);
                        create table user_external_accounts(id bigint primary key, user_id bigint references users(id), provider text, external_user_id text);
                        create table community_users(id bigint primary key, user_id bigint references users(id), ended_at timestamptz);
                        insert into users values (42, '기존회원', 'ACTIVE', '2025-01-01T00:00:00Z');
                        insert into user_credentials values (5, 42, 'legacy@example.com', 'legacy-test-hash');
                        insert into user_external_accounts values (9, 42, 'DISCORD', 'legacy-discord-id');
                        insert into community_users values (3, 42, null);
                        """);
                String script = new ClassPathResource("db/manual/add_admin_user_management.sql").getContentAsString(StandardCharsets.UTF_8);
                for (int run = 0; run < 2; run++) for (String statement : script.split(";")) {
                    if (!statement.isBlank()) sql.execute(statement);
                }
                try (var row = sql.executeQuery("select u.nickname, u.created_at, u.last_login_at, u.last_active_at, e.email, e.password_hash, d.external_user_id, d.linked_at from users u join user_credentials e on e.user_id=u.id join user_external_accounts d on d.user_id=u.id")) {
                    assertThat(row.next()).isTrue(); assertThat(row.getString("nickname")).isEqualTo("기존회원");
                    assertThat(row.getTimestamp("created_at").toInstant()).isEqualTo(java.time.Instant.parse("2025-01-01T00:00:00Z"));
                    assertThat(row.getObject("last_login_at")).isNull(); assertThat(row.getObject("last_active_at")).isNull();
                    assertThat(row.getObject("linked_at")).isNull(); assertThat(row.getString("email")).isEqualTo("legacy@example.com");
                    assertThat(row.getString("password_hash")).isEqualTo("legacy-test-hash");
                    assertThat(row.getString("external_user_id")).isEqualTo("legacy-discord-id");
                }
                try (var row = sql.executeQuery("select count(*) from pg_indexes where schemaname = '" + schema + "' and indexname in ('idx_users_created_id', 'idx_users_last_login_id', 'idx_users_active_last_active', 'idx_community_users_user_ended')")) {
                    row.next(); assertThat(row.getInt(1)).isEqualTo(4);
                }
            } finally { sql.execute("set search_path to public"); sql.execute("drop schema " + schema + " cascade"); }
        }
    }
}
