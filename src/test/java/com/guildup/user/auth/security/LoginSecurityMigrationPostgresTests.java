package com.guildup.user.auth.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Run only against an explicitly provided disposable local PostgreSQL database. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class LoginSecurityMigrationPostgresTests {
    @Test void manualSqlIsIdempotentAndPreservesExistingIdsCredentialsAndAllMonitoringRows() throws Exception {
        String schema = "login_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("TEST_POSTGRES_USER", "postgres"), System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
             var sql = connection.createStatement()) {
            sql.execute("create schema " + schema);
            try {
                sql.execute("set search_path to " + schema);
                sql.execute("""
                    CREATE TABLE users(id BIGINT PRIMARY KEY, nickname TEXT);
                    CREATE TABLE user_credentials(id BIGINT PRIMARY KEY, user_id BIGINT REFERENCES users(id), email TEXT, password_hash TEXT);
                    CREATE TABLE user_external_accounts(id BIGINT PRIMARY KEY, user_id BIGINT REFERENCES users(id), external_user_id TEXT);
                    CREATE TABLE monitoring_events(id BIGINT PRIMARY KEY, category VARCHAR(32), event_code VARCHAR(64),
                        CONSTRAINT old_categories CHECK (category IN ('HTTP','DISCORD')),
                        CONSTRAINT old_codes CHECK (event_code IN ('HTTP_5XX','DISCORD_OAUTH_FAILED')));
                    INSERT INTO users VALUES (42,'기존 사용자');
                    INSERT INTO user_credentials VALUES (10,42,'test@example.com','test-only-hash');
                    INSERT INTO user_external_accounts VALUES (11,42,'discord-test-id');
                    INSERT INTO monitoring_events VALUES (1,'HTTP','HTTP_5XX');
                    """);
                String script = new ClassPathResource("db/manual/add_login_security_monitoring.sql").getContentAsString(StandardCharsets.UTF_8);
                sql.execute(script); sql.execute(script);
                for (String code : new String[]{"LOGIN_REPEATED_FAILURE", "LOGIN_RATE_LIMITED", "LOGIN_IP_VOLUME", "LOGIN_MULTI_ACCOUNT", "LOGIN_ATTACK_SUSPECTED"}) {
                    sql.execute("insert into monitoring_events values (" + (2 + java.util.Arrays.asList("LOGIN_REPEATED_FAILURE", "LOGIN_RATE_LIMITED", "LOGIN_IP_VOLUME", "LOGIN_MULTI_ACCOUNT", "LOGIN_ATTACK_SUSPECTED").indexOf(code)) + ",'SECURITY','" + code + "')");
                }
                try (var row = sql.executeQuery("select u.id,c.user_id,c.password_hash,e.user_id,e.external_user_id from users u join user_credentials c on c.user_id=u.id join user_external_accounts e on e.user_id=u.id")) {
                    assertThat(row.next()).isTrue(); assertThat(row.getLong(1)).isEqualTo(42); assertThat(row.getLong(2)).isEqualTo(42);
                    assertThat(row.getString(3)).isEqualTo("test-only-hash"); assertThat(row.getLong(4)).isEqualTo(42); assertThat(row.getString(5)).isEqualTo("discord-test-id");
                }
                try (var row = sql.executeQuery("select count(*) from monitoring_events")) { row.next(); assertThat(row.getLong(1)).isEqualTo(6); }
                assertThatThrownBy(() -> sql.execute("insert into monitoring_events values (99,'UNKNOWN','LOGIN_RATE_LIMITED')")).isInstanceOf(java.sql.SQLException.class);
            } finally { sql.execute("set search_path to public"); sql.execute("drop schema " + schema + " cascade"); }
        }
    }
}
