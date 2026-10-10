package com.guildup.user.reset;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.user.domain.UserExternalAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Only a disposable isolated database: create-drop. Never use an operational DB URL. */
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "auth.password-reset.ip-hourly-limit=1000", "auth.password-reset.ip-daily-limit=2000",
        "auth.password-reset.token-requests-per-minute=1000", "auth.password-reset.max-events-per-minute=1000"})
class PasswordResetPostgresTests extends PasswordResetFlowTests {
    @DynamicPropertySource static void postgres(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("TEST_POSTGRES_URL"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_POSTGRES_USER", "postgres"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_POSTGRES_PASSWORD", ""));
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
    @Test void manualSqlIsRepeatableKeepsAllExistingIdentityDataAndSupportsPurposeAndMonitoringChecks() throws Exception {
        var user = signup();
        var link = external.saveAndFlush(new UserExternalAccount(user, ExternalAccountProvider.DISCORD, UUID.randomUUID().toString(), "discord"));
        var before = credentials.findByUserId(user.getId()).orElseThrow();
        long userCount = users.count(), credentialCount = credentials.count(), externalCount = external.count();
        jdbc.execute(new ClassPathResource("db/manual/add_email_verification.sql").getContentAsString(StandardCharsets.UTF_8));
        String migration = new ClassPathResource("db/manual/add_password_reset.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(migration); jdbc.execute(migration);
        assertThat(users.count()).isEqualTo(userCount); assertThat(credentials.count()).isEqualTo(credentialCount);
        assertThat(external.count()).isEqualTo(externalCount);
        assertThat(credentials.findByUserId(user.getId()).orElseThrow().getPasswordHash()).isEqualTo(before.getPasswordHash());
        assertThat(credentials.findByUserId(user.getId()).orElseThrow().isEmailVerified()).isEqualTo(before.isEmailVerified());
        assertThat(external.findById(link.getId()).orElseThrow().getUser().getId()).isEqualTo(user.getId());
        String raw = issue();
        assertThatThrownBy(() -> jdbc.update("update password_reset_tokens set purpose='EMAIL_VERIFICATION' where token_hash=?", PasswordResetTokens.hash(raw)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        confirm(raw);
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where indexname='uk_password_reset_active_credential'", Long.class)).isEqualTo(1);
    }
    @Test void migrationBackfillsOldRowsAndExtendsOlderMonitoringChecksWithoutChangingCredentials() throws Exception {
        var user = signup();
        finishQueuedMail();
        String hash = credentials.findByUserId(user.getId()).orElseThrow().getPasswordHash();
        // Simulate previous release schema in this isolated test database only.
        jdbc.execute("alter table users drop column authentication_version");
        jdbc.execute("alter table email_verification_tokens drop column purpose");
        jdbc.execute("alter table monitoring_events add constraint stage3_old_event_check check (event_code not like 'PASSWORD_RESET_%') not valid");
        String migration = new ClassPathResource("db/manual/add_password_reset.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(migration); jdbc.execute(migration);
        assertThat(jdbc.queryForObject("select authentication_version from users where id=?", Long.class, user.getId())).isZero();
        assertThat(credentials.findByUserId(user.getId()).orElseThrow().getPasswordHash()).isEqualTo(hash);
        String raw = issue(); confirm(raw);
        drainMonitoring();
        assertThat(jdbc.queryForObject("select count(*) from monitoring_events where event_code='PASSWORD_RESET_COMPLETED' and user_id=?", Long.class, user.getId())).isEqualTo(1);
        jdbc.execute("alter table monitoring_events drop constraint stage3_old_event_check");
    }
}
