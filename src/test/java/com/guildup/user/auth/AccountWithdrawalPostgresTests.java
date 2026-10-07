package com.guildup.user.auth;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 반드시 비어 있는 독립 테스트 DB만 사용한다. 운영 DB 사용 금지. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class AccountWithdrawalPostgresTests extends AccountWithdrawalTests {
    @org.junit.jupiter.api.Test
    void manualMigrationIsRepeatableAndPreservesLegacyRows() throws Exception {
        String script = new org.springframework.core.io.ClassPathResource("db/manual/add_account_withdrawal.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            String schema = "withdrawal_migration_" + java.util.UUID.randomUUID().toString().replace("-", "");
            try (var statement = connection.createStatement()) {
                statement.execute("create schema " + schema);
                try {
                    statement.execute("set search_path to " + schema);
                    statement.execute("""
                            CREATE TABLE users(id BIGINT PRIMARY KEY, nickname VARCHAR(255) NOT NULL);
                            CREATE TABLE community_users(id BIGINT PRIMARY KEY, community_id BIGINT, user_id BIGINT REFERENCES users(id));
                            CREATE TABLE community_members(id BIGINT PRIMARY KEY, nickname VARCHAR(255));
                            CREATE TABLE community_member_accounts(id BIGINT PRIMARY KEY);
                            CREATE TABLE discord_voice_sessions(id BIGINT PRIMARY KEY);
                            CREATE TABLE monitoring_events(id BIGINT PRIMARY KEY, event_code VARCHAR(64)
                                CONSTRAINT ck_old_code CHECK (event_code IN ('HTTP_5XX', 'DISCORD_OAUTH_FAILED')));
                            INSERT INTO users VALUES(1,'기존 사용자');
                            INSERT INTO community_users VALUES(1,1,1);
                            INSERT INTO community_members VALUES(1,'기존 클랜원');
                            INSERT INTO monitoring_events VALUES(1,'HTTP_5XX');
                            """);
                    statement.execute(script);
                    statement.execute(script);
                    try (var rows = statement.executeQuery("select nickname,status,withdrawn_at from users where id=1")) {
                        org.assertj.core.api.Assertions.assertThat(rows.next()).isTrue();
                        org.assertj.core.api.Assertions.assertThat(rows.getString(1)).isEqualTo("기존 사용자");
                        org.assertj.core.api.Assertions.assertThat(rows.getString(2)).isEqualTo("ACTIVE");
                        org.assertj.core.api.Assertions.assertThat(rows.getObject(3)).isNull();
                    }
                    statement.execute("insert into monitoring_events values(2,'USER_WITHDRAWN')");
                    try (var rows = statement.executeQuery("select count(*) from community_users")) {
                        rows.next(); org.assertj.core.api.Assertions.assertThat(rows.getLong(1)).isEqualTo(1);
                    }
                } finally {
                    statement.execute("set search_path to public");
                    statement.execute("drop schema " + schema + " cascade");
                }
            }
            return null;
        });
    }

    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("TEST_POSTGRES_URL"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
    }
}
