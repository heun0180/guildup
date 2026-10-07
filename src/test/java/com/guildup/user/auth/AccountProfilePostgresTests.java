package com.guildup.user.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** TEST_POSTGRES_URL은 폐기 가능한 독립 테스트 DB여야 한다. 운영 DB 사용 금지. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class AccountProfilePostgresTests extends AccountProfileTests {
    @Autowired JdbcTemplate jdbc;

    @Test void migrationIsRepeatableUsesNullableDateAndPreservesExistingIdentities() throws Exception {
        String script = new org.springframework.core.io.ClassPathResource("db/manual/add_account_profile.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            String schema = "profile_migration_" + java.util.UUID.randomUUID().toString().replace("-", "");
            try (var statement = connection.createStatement()) {
                statement.execute("create schema " + schema);
                try {
                    statement.execute("set search_path to " + schema);
                    statement.execute("""
                            CREATE TABLE users(id BIGINT PRIMARY KEY, nickname VARCHAR(255) NOT NULL);
                            CREATE TABLE user_external_accounts(id BIGINT PRIMARY KEY, user_id BIGINT REFERENCES users(id), external_username VARCHAR(255));
                            INSERT INTO users VALUES(1,'기존 GuildUp 이름');
                            INSERT INTO user_external_accounts VALUES(1,1,'apple_123');
                            """);
                    statement.execute(script);
                    statement.execute(script);
                    try (var rows = statement.executeQuery("select nickname,birth_date from users where id=1")) {
                        assertThatRow(rows);
                    }
                    try (var rows = statement.executeQuery("select external_username,external_display_name,external_avatar_url from user_external_accounts where id=1")) {
                        org.assertj.core.api.Assertions.assertThat(rows.next()).isTrue();
                        org.assertj.core.api.Assertions.assertThat(rows.getString(1)).isEqualTo("apple_123");
                        org.assertj.core.api.Assertions.assertThat(rows.getObject(2)).isNull();
                        org.assertj.core.api.Assertions.assertThat(rows.getObject(3)).isNull();
                    }
                    try (var rows = statement.executeQuery("select data_type,is_nullable from information_schema.columns where table_schema='" + schema + "' and table_name='users' and column_name='birth_date'")) {
                        org.assertj.core.api.Assertions.assertThat(rows.next()).isTrue();
                        org.assertj.core.api.Assertions.assertThat(rows.getString(1)).isEqualTo("date");
                        org.assertj.core.api.Assertions.assertThat(rows.getString(2)).isEqualTo("YES");
                    }
                } finally {
                    statement.execute("set search_path to public");
                    statement.execute("drop schema " + schema + " cascade");
                }
            }
            return null;
        });
    }

    private void assertThatRow(java.sql.ResultSet rows) throws java.sql.SQLException {
        org.assertj.core.api.Assertions.assertThat(rows.next()).isTrue();
        org.assertj.core.api.Assertions.assertThat(rows.getString(1)).isEqualTo("기존 GuildUp 이름");
        org.assertj.core.api.Assertions.assertThat(rows.getObject(2)).isNull();
        org.assertj.core.api.Assertions.assertThat(rows.next()).isFalse();
    }

    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("TEST_POSTGRES_URL"));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
    }
}
