package com.guildup.user.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;

import java.sql.Timestamp;
import java.time.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserActivityServiceTests {
    JdbcTemplate jdbc;
    UserActivityService service;
    MutableClock clock;
    MockHttpSession session;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:activity-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = spy(new JdbcTemplate(ds));
        jdbc.execute("create table users(id bigint primary key, status varchar(20), authentication_version bigint, last_login_at timestamp with time zone, last_active_at timestamp with time zone)");
        jdbc.update("insert into users values (1, 'ACTIVE', 0, null, null)");
        clock = new MutableClock(); session = new MockHttpSession();
        service = new UserActivityService(jdbc, clock, new DataSourceTransactionManager(ds));
        clearInvocations(jdbc);
    }

    @Test void authenticatedRequestsWriteOnlyOncePerTenMinutesAndLoginIsIndependent() {
        service.activity(1L, session);
        for (int i = 0; i < 100; i++) service.activity(1L, session);
        assertThat(updates()).isEqualTo(1);
        assertThat(active()).isEqualTo(clock.instant());
        assertThat(login()).isNull();
        clock.at = clock.at.plusSeconds(599); service.activity(1L, session); assertThat(updates()).isEqualTo(1);
        clock.at = clock.at.plusSeconds(1); service.activity(1L, session); assertThat(updates()).isEqualTo(2);
        assertThat(active()).isEqualTo(clock.instant());
        service.login(1L, 0, session); assertThat(login()).isEqualTo(clock.instant());
        service.activity(1L, session); assertThat(updates()).isEqualTo(3);
    }

    @Test void otherSessionsCannotRewriteRecentActivityAndTimestampsNeverMoveBackwards() {
        service.activity(1L, session);
        Instant first = active();
        clock.at = clock.at.plusSeconds(60); service.activity(1L, new MockHttpSession());
        assertThat(active()).isEqualTo(first);
        service.login(1L, 0, session); Instant latest = login();
        clock.at = clock.at.minusSeconds(100); service.login(1L, 0, new MockHttpSession());
        assertThat(login()).isEqualTo(latest); assertThat(active()).isEqualTo(latest);
    }

    @Test void anotherBrowserUsesTheStoredWindowInsteadOfDelayingActivityForTwentyMinutes() {
        service.activity(1L, session);
        var another = new MockHttpSession();
        clock.at = clock.at.plusSeconds(9 * 60); service.activity(1L, another);
        assertThat(active()).isEqualTo(clock.instant().minusSeconds(9 * 60));
        clock.at = clock.at.plusSeconds(60); service.activity(1L, another);
        assertThat(active()).isEqualTo(clock.instant());
    }

    @Test void withdrawnAccountsAndStaleAuthenticationVersionCannotRecordLogin() {
        service.login(1L, 9, session); assertThat(login()).isNull();
        jdbc.update("update users set status = 'WITHDRAWN' where id = 1");
        service.login(1L, 0, session); service.activity(1L, new MockHttpSession());
        assertThat(login()).isNull(); assertThat(active()).isNull();
    }

    @Test void missingColumnsOrInvalidatedSessionDoNotBreakAuthenticationAndFailuresAreThrottled() {
        jdbc.execute("alter table users drop column last_active_at");
        assertThatCode(() -> service.login(1L, 0, session)).doesNotThrowAnyException();
        service.activity(1L, session);
        long attempted = updates();
        service.activity(1L, session); assertThat(updates()).isEqualTo(attempted);
        session.invalidate();
        assertThatCode(() -> service.activity(1L, session)).doesNotThrowAnyException();
    }

    private long updates() { return mockingDetails(jdbc).getInvocations().stream().filter(call ->
            call.getMethod().getName().equals("update") && call.getMethod().getParameterCount() == 2
                    && call.getMethod().getParameterTypes()[1] == Object[].class).count(); }
    private Instant active() { return jdbc.queryForObject("select last_active_at from users where id = 1", (rs, row) -> { Timestamp t = rs.getTimestamp(1); return t == null ? null : t.toInstant(); }); }
    private Instant login() { return jdbc.queryForObject("select last_login_at from users where id = 1", (rs, row) -> { Timestamp t = rs.getTimestamp(1); return t == null ? null : t.toInstant(); }); }
    static class MutableClock extends Clock {
        Instant at = Instant.parse("2026-10-10T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return at; }
    }
}
