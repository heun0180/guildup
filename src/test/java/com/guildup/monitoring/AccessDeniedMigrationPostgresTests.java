package com.guildup.monitoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Only an explicitly provided disposable local PostgreSQL database may be used. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class AccessDeniedMigrationPostgresTests {
    @Test void diagnosticConstraintUpdateIsIdempotentAndPreservesExistingAccountsAndMonitoringRows() throws Exception {
        String schema = "denial_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("TEST_POSTGRES_USER", "postgres"), System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
             var sql = connection.createStatement()) {
            sql.execute("create schema " + schema);
            try {
                sql.execute("set search_path to " + schema);
                sql.execute("""
                    CREATE TABLE users(id BIGINT PRIMARY KEY);
                    CREATE TABLE user_credentials(id BIGINT PRIMARY KEY, user_id BIGINT REFERENCES users(id), password_hash TEXT);
                    CREATE TABLE user_external_accounts(id BIGINT PRIMARY KEY, user_id BIGINT REFERENCES users(id), external_user_id TEXT);
                    CREATE TABLE monitoring_events(id BIGINT PRIMARY KEY, category VARCHAR(32), event_code VARCHAR(64),
                        CONSTRAINT old_categories CHECK (category IN ('HTTP','DISCORD')),
                        CONSTRAINT old_codes CHECK (event_code IN ('HTTP_5XX','DISCORD_OAUTH_FAILED')));
                    INSERT INTO users VALUES (42);
                    INSERT INTO user_credentials VALUES (10,42,'existing-test-hash');
                    INSERT INTO user_external_accounts VALUES (11,42,'existing-discord-test-id');
                    INSERT INTO monitoring_events VALUES (1,'HTTP','HTTP_5XX'),(2,'DISCORD','DISCORD_OAUTH_FAILED');
                    """);
                String script = new ClassPathResource("db/manual/add_access_denied_monitoring.sql").getContentAsString(StandardCharsets.UTF_8);
                sql.execute(script); sql.execute(script);
                sql.execute("insert into monitoring_events values (3,'SECURITY','HTTP_ACCESS_DENIED')");
                try (var row = sql.executeQuery("select u.id,c.user_id,c.password_hash,e.user_id,e.external_user_id from users u join user_credentials c on c.user_id=u.id join user_external_accounts e on e.user_id=u.id")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getLong(1)).isEqualTo(42); assertThat(row.getLong(2)).isEqualTo(42);
                    assertThat(row.getString(3)).isEqualTo("existing-test-hash"); assertThat(row.getLong(4)).isEqualTo(42);
                    assertThat(row.getString(5)).isEqualTo("existing-discord-test-id");
                }
                try (var row = sql.executeQuery("select count(*) from monitoring_events where (id=1 and category='HTTP' and event_code='HTTP_5XX') or (id=2 and category='DISCORD' and event_code='DISCORD_OAUTH_FAILED')")) {
                    row.next(); assertThat(row.getInt(1)).isEqualTo(2);
                }
                assertThatThrownBy(() -> sql.execute("insert into monitoring_events values (99,'UNKNOWN','HTTP_ACCESS_DENIED')")).isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> sql.execute("insert into monitoring_events values (99,'SECURITY','UNKNOWN')")).isInstanceOf(java.sql.SQLException.class);
            } finally { sql.execute("set search_path to public"); sql.execute("drop schema " + schema + " cascade"); }
        }
    }
}
