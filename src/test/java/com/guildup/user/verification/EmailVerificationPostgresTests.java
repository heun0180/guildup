package com.guildup.user.verification;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Only the explicitly supplied, disposable PostgreSQL cluster is used. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "auth.email-verification.enforce-new-users=true", "auth.email-verification.ip-hourly-limit=1000",
        "auth.email-verification.ip-daily-limit=2000", "auth.email-verification.confirm-per-minute=1000"})
class EmailVerificationPostgresTests extends EmailVerificationFlowTests {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("TEST_POSTGRES_URL"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
    @BeforeEach void applyProductionConstraints() throws Exception {
        jdbc.execute(new ClassPathResource("db/manual/add_email_verification.sql").getContentAsString(StandardCharsets.UTF_8));
    }
    @Test void migrationIsIdempotentPreservesLegacyDataAndExtendsMonitoringConstraints() throws Exception {
        String schema = "email_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(System.getenv("TEST_POSTGRES_URL"),
                System.getenv().getOrDefault("TEST_POSTGRES_USER", "postgres"), System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
             var sql = connection.createStatement()) {
            sql.execute("create schema " + schema);
            try {
                sql.execute("set search_path to " + schema);
                sql.execute("""
                    CREATE TABLE users(id BIGINT PRIMARY KEY, nickname TEXT);
                    CREATE TABLE user_credentials(id BIGINT PRIMARY KEY, user_id BIGINT REFERENCES users(id), email TEXT, password_hash TEXT, email_verified BOOLEAN NOT NULL DEFAULT FALSE);
                    CREATE TABLE user_external_accounts(id BIGINT PRIMARY KEY, user_id BIGINT REFERENCES users(id), external_user_id TEXT);
                    CREATE TABLE community_users(id BIGINT PRIMARY KEY, user_id BIGINT REFERENCES users(id));
                    CREATE TABLE monitoring_events(id BIGINT PRIMARY KEY, category VARCHAR(32), event_code VARCHAR(64),
                        CONSTRAINT old_category CHECK(category IN ('HTTP','DISCORD')),
                        CONSTRAINT old_event_code CHECK(event_code IN ('HTTP_5XX','DISCORD_OAUTH_FAILED')));
                    INSERT INTO users VALUES(42,'legacy');
                    INSERT INTO user_credentials VALUES(10,42,'legacy@example.com','existing-hash',FALSE);
                    INSERT INTO user_external_accounts VALUES(11,42,'existing-discord');
                    INSERT INTO community_users VALUES(21,42);
                    INSERT INTO monitoring_events VALUES(1,'HTTP','HTTP_5XX');
                    """);
                String script = new ClassPathResource("db/manual/add_email_verification.sql").getContentAsString(StandardCharsets.UTF_8);
                sql.execute(script); sql.execute(script);
                try (var row = sql.executeQuery("select u.id,c.user_id,c.password_hash,c.email_verified,c.verification_required,e.external_user_id,m.id from users u join user_credentials c on c.user_id=u.id join user_external_accounts e on e.user_id=u.id join community_users m on m.user_id=u.id")) {
                    assertThat(row.next()).isTrue(); assertThat(row.getLong(1)).isEqualTo(42); assertThat(row.getLong(2)).isEqualTo(42);
                    assertThat(row.getString(3)).isEqualTo("existing-hash"); assertThat(row.getBoolean(4)).isFalse(); assertThat(row.getBoolean(5)).isFalse();
                    assertThat(row.getString(6)).isEqualTo("existing-discord"); assertThat(row.getLong(7)).isEqualTo(21);
                }
                int id = 2;
                for (String code : new String[]{"EMAIL_VERIFICATION_MAIL_FAILED", "EMAIL_VERIFICATION_INVALID", "EMAIL_VERIFICATION_EXPIRED", "EMAIL_VERIFICATION_RATE_LIMITED", "EMAIL_VERIFICATION_ABUSE", "EMAIL_VERIFICATION_COMPLETED"})
                    sql.execute("insert into monitoring_events values(" + id++ + ",'SECURITY','" + code + "')");
                assertThatThrownBy(() -> sql.execute("insert into monitoring_events values(99,'UNKNOWN','EMAIL_VERIFICATION_INVALID')")).isInstanceOf(SQLException.class);
                sql.execute("insert into email_verification_tokens(credential_id,token_hash,created_at,expires_at,delivery) values(10,repeat('a',64),now(),now()+interval '24 hours','SENT')");
                assertThatThrownBy(() -> sql.execute("insert into email_verification_tokens(credential_id,token_hash,created_at,expires_at,delivery) values(10,repeat('b',64),now(),now()+interval '24 hours','SENT')"))
                        .isInstanceOf(SQLException.class);
                sql.execute("delete from user_credentials where id=10");
                try (var row = sql.executeQuery("select count(*) from email_verification_tokens")) { row.next(); assertThat(row.getLong(1)).isZero(); }
                try (var row = sql.executeQuery("select count(*) from users")) { row.next(); assertThat(row.getLong(1)).isEqualTo(1); }
            } finally { sql.execute("set search_path to public"); sql.execute("drop schema " + schema + " cascade"); }
        }
    }
}
