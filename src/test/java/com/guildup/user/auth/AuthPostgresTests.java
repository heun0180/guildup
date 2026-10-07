package com.guildup.user.auth;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.user.domain.UserExternalAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** TEST_POSTGRES_URL은 create-drop을 허용한 폐기 가능한 격리 DB여야 한다. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "discord.oauth.client-id=test-client", "discord.oauth.client-secret=test-secret",
        "discord.oauth.redirect-uri=http://localhost/api/discord/oauth/callback"})
class AuthPostgresTests extends AuthFlowTests {
    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("TEST_POSTGRES_URL"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void deploymentSqlIsRepeatableAndKeepsAllExistingUsersCredentialsAndExternalAccounts() throws Exception {
        var user = emailAuth.register(new com.guildup.user.auth.dto.SignupRequest("migration-" + UUID.randomUUID() + "@example.com",
                PASSWORD, PASSWORD, "기존 사용자"));
        var account = externalAccounts.saveAndFlush(new UserExternalAccount(user, ExternalAccountProvider.DISCORD,
                "migration-" + UUID.randomUUID(), "discord"));
        var credential = credentials.findByUserId(user.getId()).orElseThrow();
        long userCount = users.count(), credentialCount = credentials.count(), externalCount = externalAccounts.count();
        String sql = new ClassPathResource("db/manual/add_user_credentials.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(sql);
        jdbc.execute(sql);
        assertThat(users.count()).isEqualTo(userCount);
        assertThat(credentials.count()).isEqualTo(credentialCount);
        assertThat(externalAccounts.count()).isEqualTo(externalCount);
        assertThat(credentials.findByUserId(user.getId()).orElseThrow().getPasswordHash()).isEqualTo(credential.getPasswordHash());
        assertThat(externalAccounts.findById(account.getId()).orElseThrow().getUser().getId()).isEqualTo(user.getId());
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where tablename = 'user_credentials' and indexname = 'uk_user_credential_email_normalized'", Long.class)).isEqualTo(1);
    }
}
