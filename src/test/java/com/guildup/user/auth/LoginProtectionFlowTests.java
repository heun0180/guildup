package com.guildup.user.auth;

import com.guildup.discord.bot.DiscordBot;
import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.repository.MonitoringEventRepository;
import com.guildup.user.auth.dto.*;
import com.guildup.user.auth.security.*;
import com.guildup.user.auth.service.*;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:login-protection;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "security.login.ip-short-limit=12", "security.login.ip-long-limit=30",
        "security.login.spray-accounts=3", "security.login.spray-failures=3"
})
@AutoConfigureMockMvc
@Import(LoginProtectionFlowTests.Time.class)
class LoginProtectionFlowTests {
    @TestConfiguration static class Time {
        @Bean @Primary TestClock loginTestClock() { return new TestClock(); }
    }
    static class TestClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-08T00:00:00Z");
        void advance(Duration value) { now = now.plus(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    @Autowired MockMvc mvc;
    @Autowired TestClock clock;
    @Autowired InMemoryLoginAttemptStore store;
    @Autowired MonitoringEventRepository events;
    @Autowired UserRepository users;
    @MockitoSpyBean CredentialAuthService credentials;
    @MockitoSpyBean PasswordEncoder encoder;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    // This suite measures login attack events; verification mail is covered independently.
    @MockitoBean com.guildup.user.verification.EmailVerificationMailService verificationMail;
    final ObjectMapper json = new ObjectMapper();
    static final String PASSWORD = "GuildUp123!", WRONG = "WrongPass123!";
    String email;

    @BeforeEach void setup() {
        clock.advance(Duration.ofMinutes(20)); store.cleanup(); events.deleteAll();
        email = UUID.randomUUID() + "@example.com";
    }
    User member() { return credentials.register(new SignupRequest(email, PASSWORD, PASSWORD, "로그인 테스트")); }
    MockHttpServletRequestBuilder login(String target, String password, String ip) {
        var session = new MockHttpSession();
        return post("/api/auth/login").session(session).header("X-CSRF-Token", SessionCsrfTokens.getOrCreate(session))
                .contentType("application/json").content(json.writeValueAsString(Map.of("email", target, "password", password)))
                .with(request -> { request.setRemoteAddr(ip); return request; });
    }

    @Test void normalLoginWrongPasswordOnceAndSuccessAfterFailureKeepSameUserAndNoSecurityLogs() throws Exception {
        var user = member();
        mvc.perform(login(email, WRONG, "203.0.113.1")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 올바르지 않습니다."));
        var result = mvc.perform(login(email, PASSWORD, "203.0.113.1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId())).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session.getAttribute(CurrentUserSession.USER_ID)).isEqualTo(user.getId());
        assertThat(events.count()).isZero();
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-Token", SessionCsrfTokens.getOrCreate(session)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test void repeatedFailureChecksLimitBeforeVerifierAndDoesNotSlideAndSuccessResetsIt() throws Exception {
        member();
        for (int i = 0; i < 5; i++) mvc.perform(login(email, WRONG, "203.0.113.2")).andExpect(status().isUnauthorized());
        clearInvocations(credentials, encoder);
        mvc.perform(login(email, PASSWORD, "203.0.113.2")).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2")).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));
        verify(credentials, never()).login(any()); verify(encoder, never()).matches(anyString(), anyString());
        clock.advance(Duration.ofSeconds(1));
        mvc.perform(login(email, PASSWORD, "203.0.113.2")).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1"));
        clock.advance(Duration.ofSeconds(1));
        mvc.perform(login(email, PASSWORD, "203.0.113.2")).andExpect(status().isOk());
        mvc.perform(login(email, WRONG, "203.0.113.2")).andExpect(status().isUnauthorized());
        mvc.perform(login(email, PASSWORD, "203.0.113.2")).andExpect(status().isOk());
    }

    @Test void existingAndMissingAccountsHaveIdenticalFailuresCooldownAndPasswordVerificationWork() throws Exception {
        member();
        String missing = "missing-" + email;
        for (int i = 0; i < 5; i++) {
            var existing = mvc.perform(login("  " + email.toUpperCase(Locale.ROOT) + "  ", WRONG, "203.0.113.3"))
                    .andExpect(status().isUnauthorized()).andReturn().getResponse();
            var unknown = mvc.perform(login(missing, WRONG, "203.0.113.4"))
                    .andExpect(status().isUnauthorized()).andReturn().getResponse();
            assertThat(existing.getContentAsString()).isEqualTo(unknown.getContentAsString());
        }
        verify(encoder, times(10)).matches(eq(WRONG), startsWith("{bcrypt}"));
        var existing = mvc.perform(login(email, WRONG, "203.0.113.3")).andExpect(status().isTooManyRequests()).andReturn().getResponse();
        var unknown = mvc.perform(login(missing, WRONG, "203.0.113.4")).andExpect(status().isTooManyRequests()).andReturn().getResponse();
        assertThat(existing.getContentAsString()).isEqualTo(unknown.getContentAsString());
        assertThat(existing.getHeader("Retry-After")).isEqualTo(unknown.getHeader("Retry-After"));
        clock.advance(Duration.ofMinutes(15));
        mvc.perform(login(missing, WRONG, "203.0.113.4")).andExpect(status().isUnauthorized());
    }

    @Test void ipCountsDifferentEmailsAndForgedHeadersCannotChangeBudget() throws Exception {
        var user = member();
        doReturn(user).when(credentials).login(any());
        for (int i = 0; i < 12; i++) mvc.perform(login("member" + i + "@example.com", PASSWORD, "203.0.113.5")
                .header("X-Forwarded-For", "198.51.100." + (i + 1)).header("Forwarded", "for=198.51.100.99")
                .header("X-Real-IP", "198.51.100.99")).andExpect(status().isOk());
        mvc.perform(login(email, PASSWORD, "203.0.113.5")).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"));
        assertThat(events.findAll()).anyMatch(event -> event.getEventCode() == MonitoringEventCode.LOGIN_IP_VOLUME);
        clock.advance(Duration.ofMinutes(1));
        mvc.perform(login(email, PASSWORD, "203.0.113.5")).andExpect(status().isOk());
    }

    @Test void multiAccountUnknownEmailPatternIsSavedAndIpv6PrivacyAddressesCannotRotateAwayRestriction() throws Exception {
        for (int i = 0; i < 3; i++) mvc.perform(login("unknown" + i + "-" + email, WRONG, "2001:db8:1::" + (i + 1))).andExpect(status().isUnauthorized());
        mvc.perform(login("fourth-" + email, WRONG, "2001:0db8:0001::abcd")).andExpect(status().isTooManyRequests());
        var event = events.findAll().stream().filter(row -> row.getEventCode() == MonitoringEventCode.LOGIN_MULTI_ACCOUNT).findFirst().orElseThrow();
        assertThat(event.getCategory()).isEqualTo(MonitoringCategory.SECURITY);
        assertThat(event.getMetadata()).containsEntry("distinctAccounts", 3).containsEntry("rateLimited", true).containsEntry("riskLevel", "HIGH");
    }

    @Test void parallelHttpRequestsFromDifferentSessionsAndIpsOnlyInvokeOnePasswordCheck() throws Exception {
        member();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(invocation -> { entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("verification not released"); return false; })
                .when(encoder).matches(eq(WRONG), anyString());
        try (var pool = Executors.newFixedThreadPool(10)) {
            var first = pool.submit(() -> mvc.perform(login(email, WRONG, "203.0.113.10")).andReturn().getResponse().getStatus());
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            List<Future<Integer>> others = new ArrayList<>();
            for (int i = 0; i < 8; i++) { String ip = "203.0.113." + (20 + i); others.add(pool.submit(() -> mvc.perform(login(email, WRONG, ip)).andReturn().getResponse().getStatus())); }
            try { for (var result : others) assertThat(result.get(3, TimeUnit.SECONDS)).isEqualTo(429); }
            finally { release.countDown(); }
            assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo(401);
            verify(encoder, times(1)).matches(eq(WRONG), anyString());
        } finally { release.countDown(); }
    }

    @Test void securityEventsContainOnlyProtectedIdentifiersAndServerRequestIdAndAreAdminOnly() throws Exception {
        var user = member();
        String supplied = "sensitive-request-code";
        for (int i = 0; i < 5; i++) mvc.perform(login(email, WRONG, "203.0.113.8")).andExpect(status().isUnauthorized());
        var limited = mvc.perform(login(email, PASSWORD, "203.0.113.8").header("X-Request-ID", supplied))
                .andExpect(status().isTooManyRequests()).andReturn().getResponse();
        assertThat(limited.getHeader("X-Request-ID")).isNotEqualTo(supplied);
        var record = events.findAll().stream().filter(event -> event.getEventCode() == MonitoringEventCode.LOGIN_RATE_LIMITED).findFirst().orElseThrow();
        assertThat(record.getMetadata().get("requestId")).isEqualTo(limited.getHeader("X-Request-ID"));
        String text = json.writeValueAsString(events.findAll().stream().map(event -> Map.of("metadata", event.getMetadata(), "reference", event.getReferenceId(), "message", event.getMessage())).toList());
        assertThat(text).doesNotContain(email, PASSWORD, WRONG, "203.0.113.8", supplied, "{bcrypt}", "JSESSIONID", "csrf", "accessToken", "refreshToken");
        assertThat(record.getMetadata().get("accountIdHash").toString()).matches("[a-f0-9]{64}");
        var ordinary = new MockHttpSession(); ordinary.setAttribute(CurrentUserSession.USER_ID, user.getId());
        mvc.perform(get("/api/developer/monitoring/events").session(ordinary).param("group", "SECURITY")).andExpect(status().isForbidden());
        mvc.perform(get("/api/developer/monitoring/events").param("category", "SECURITY")).andExpect(status().isUnauthorized());
        var admin = users.save(new User("개발자"));
        // Only the isolated test DB receives this test fixture role.
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("update users set system_role='SYSTEM_ADMIN' where id=?", admin.getId());
        var adminSession = new MockHttpSession(); adminSession.setAttribute(CurrentUserSession.USER_ID, admin.getId());
        mvc.perform(get("/api/developer/monitoring/events").session(adminSession).param("group", "SECURITY"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].category").value("SECURITY"));
    }
    @Autowired javax.sql.DataSource dataSource;

    @Test void csrfRejectionPrecedesLimiterAndDoesNotConsumeLoginBudget() throws Exception {
        member();
        var anonymous = new MockHttpSession();
        for (int i = 0; i < 15; i++) mvc.perform(post("/api/auth/login").session(anonymous).contentType("application/json")
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_MISSING"));
        mvc.perform(login(email, PASSWORD, "127.0.0.1")).andExpect(status().isOk());
    }
}
