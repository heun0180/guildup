package com.guildup.user.reset;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.repository.CommunityUserRepository;
import com.guildup.community.service.CommunityService;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.repository.MonitoringEventRepository;
import com.guildup.user.auth.dto.*;
import com.guildup.user.auth.exception.AuthException;
import com.guildup.user.auth.service.*;
import com.guildup.user.domain.*;
import com.guildup.user.repository.*;
import com.guildup.user.verification.*;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:password-reset;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "auth.password-reset.ip-hourly-limit=1000", "auth.password-reset.ip-daily-limit=2000",
        "auth.password-reset.token-requests-per-minute=1000", "auth.password-reset.max-events-per-minute=1000"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(PasswordResetFlowTests.ClockConfig.class)
class PasswordResetFlowTests {
    static final String PASSWORD = "GuildUp123!", NEW_PASSWORD = "Changed123!";
    @Autowired UserRepository users;
    @Autowired UserCredentialRepository credentials;
    @Autowired UserExternalAccountRepository external;
    @Autowired PasswordResetTokenRepository tokens;
    @Autowired PasswordResetService reset;
    @Autowired PasswordResetRequests requests;
    @Autowired PasswordResetDeliveryTransactions delivery;
    @Autowired PasswordResetRetention retention;
    @Autowired CredentialAuthService auth;
    @Autowired AuthSessionService sessions;
    @Autowired DiscordLoginService discord;
    @Autowired AccountWithdrawalService withdrawals;
    @Autowired EmailVerificationService verification;
    @Autowired EmailVerificationTokenRepository verificationTokens;
    @Autowired CommunityService communities;
    @Autowired CommunityUserRepository memberships;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @Autowired MonitoringEventRepository events;
    @Autowired AdjustableClock clock;
    @Autowired @Qualifier("passwordResetRequestExecutor") ThreadPoolTaskExecutor requestExecutor;
    @Autowired @Qualifier("passwordResetMailExecutor") ThreadPoolTaskExecutor mailExecutor;
    @Autowired @Qualifier("emailVerificationExecutor") ThreadPoolTaskExecutor verificationExecutor;
    @Autowired PasswordResetProperties resetProperties;
    @Autowired PasswordResetMailQuotaRepository quota;
    @Autowired PasswordResetEvents resetEvents;
    @Autowired org.springframework.context.ApplicationEventPublisher publisher;
    @Autowired com.guildup.monitoring.config.MonitoringEventExecutor monitoringExecutor;
    @MockitoBean PasswordResetMailService mail;
    @MockitoBean EmailVerificationMailService verificationMail;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    final ConcurrentMap<String, String> delivered = new ConcurrentHashMap<>();
    String email;
    @DynamicPropertySource static void enableMockMail(DynamicPropertyRegistry properties) {
        properties.add("auth.password-reset.mail-enabled", () -> "true");
        properties.add("monitoring.events.async", () -> "true");
    }
    @BeforeEach void setup() throws Exception {
        clock.now.set(Instant.parse("2026-10-09T01:00:00Z")); email = UUID.randomUUID() + "@example.com";
        jdbc.update("update password_reset_mail_quota set budget_day = ?, budget_month = ?, daily_count = 0, monthly_count = 0 where id = 1",
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC), LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).withDayOfMonth(1));
        doAnswer(call -> {
            String address = call.getArgument(0), raw = call.getArgument(1);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(tokens.findByTokenHash(PasswordResetTokens.hash(raw))).isPresent();
            delivered.put(address, raw); return null;
        }).when(mail).send(anyString(), anyString());
    }
    User signup() { return auth.register(new SignupRequest(email, PASSWORD, PASSWORD, "기존 이메일 회원")); }
    @AfterEach void finishQueuedMail() throws Exception {
        drainRequests();
        for (int i = 0; i < 250 && (mailExecutor.getActiveCount() != 0 || !mailExecutor.getThreadPoolExecutor().getQueue().isEmpty()); i++) Thread.sleep(20);
        assertThat(mailExecutor.getActiveCount()).isZero(); assertThat(mailExecutor.getThreadPoolExecutor().getQueue()).isEmpty();
        for (int i = 0; i < 250 && (verificationExecutor.getActiveCount() != 0 || !verificationExecutor.getThreadPoolExecutor().getQueue().isEmpty()); i++) Thread.sleep(20);
        assertThat(verificationExecutor.getActiveCount()).isZero(); assertThat(verificationExecutor.getThreadPoolExecutor().getQueue()).isEmpty();
        drainMonitoring();
    }
    void drainMonitoring() throws Exception { monitoringExecutor.submit(() -> {}).get(5, TimeUnit.SECONDS); }
    void advance(long seconds) { clock.now.updateAndGet(value -> value.plusSeconds(seconds)); }
    String issue() throws Exception {
        delivered.remove(email); reset.issue(email); return raw();
    }
    String raw() throws Exception {
        for (int i = 0; i < 250; i++) {
            String raw = delivered.get(email);
            if (raw != null && tokens.findByTokenHash(PasswordResetTokens.hash(raw)).map(t -> t.getDelivery() == PasswordResetToken.Delivery.SENT).orElse(false)) return raw;
            Thread.sleep(20);
        }
        throw new AssertionError("Mock reset email was not delivered");
    }
    void drainRequests() throws Exception {
        for (int i = 0; i < 250 && (requestExecutor.getActiveCount() != 0 || requestExecutor.getThreadPoolExecutor().getQueue().size() != 0); i++) Thread.sleep(20);
        assertThat(requestExecutor.getActiveCount()).isZero(); assertThat(requestExecutor.getThreadPoolExecutor().getQueue()).isEmpty();
    }
    MockHttpSession loggedIn(User user) {
        var request = new MockHttpServletRequest(); var session = new MockHttpSession(); request.setSession(session);
        sessions.authenticate(request, user); return session;
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder securePost(String path, MockHttpSession session, String json) {
        return post(path).session(session).header(SessionCsrfTokens.HEADER, SessionCsrfTokens.getOrCreate(session))
                .contentType("application/json").content(json);
    }
    void confirm(String raw) { reset.confirmEncoded(raw, encoder.encode(NEW_PASSWORD)); }
    void reject(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }
    @Test void requestResponsesNeverRevealExistenceMethodVerificationOrWithdrawal() throws Exception {
        var user = signup();
        var discordOnly = users.saveAndFlush(new User("Discord 전용"));
        external.saveAndFlush(new UserExternalAccount(discordOnly, ExternalAccountProvider.DISCORD, UUID.randomUUID().toString(), "discord"));
        String missing = UUID.randomUUID() + "@example.com", discordAddress = UUID.randomUUID() + "@example.com";
        String body = null;
        for (String address : List.of(email, missing, discordAddress, "bad-email")) {
            var result = mvc.perform(securePost("/api/auth/password-reset/request", new MockHttpSession(), "{\"email\":\"" + address + "\"}"))
                    .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.message").value(PasswordResetController.ACCEPTED_MESSAGE)).andReturn();
            if (body == null) body = result.getResponse().getContentAsString();
            else assertThat(result.getResponse().getContentAsString()).isEqualTo(body);
        }
        drainRequests(); raw();
        verify(mail, times(1)).send(eq(email), anyString());
        assertThat(credentials.findByUserId(discordOnly.getId())).isEmpty();
        assertThat(external.findByUserId(discordOnly.getId())).hasSize(1);
        var proof = withdrawals.verifyPassword(user.getId(), PASSWORD); withdrawals.withdraw(user.getId(), proof, true);
        mvc.perform(securePost("/api/auth/password-reset/request", new MockHttpSession(), "{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted()).andExpect(content().string(body));
        drainRequests(); verify(mail, times(1)).send(eq(email), anyString());
    }
    @Test void storesOnlyPurposeSeparatedHashWith256BitsAndThirtyMinuteExpiry() throws Exception {
        var user = signup(); String raw = issue(); var token = tokens.findByTokenHash(PasswordResetTokens.hash(raw)).orElseThrow();
        assertThat(Base64.getUrlDecoder().decode(raw)).hasSize(32);
        assertThat(PasswordResetTokens.hash(raw)).isNotEqualTo(EmailVerificationTokens.hash(raw));
        assertThat(Duration.between(token.getCreatedAt(), token.getExpiresAt())).isEqualTo(Duration.ofMinutes(30));
        assertThat(token.getPurpose()).isEqualTo(com.guildup.user.auth.token.AuthTokenPurpose.PASSWORD_RESET);
        assertThat(jdbc.queryForObject("select token_hash from password_reset_tokens where id = ?", String.class, token.getId()))
                .isEqualTo(PasswordResetTokens.hash(raw)).doesNotContain(raw);
        assertThat(credentials.findByUserId(user.getId()).orElseThrow().isEmailVerified()).isFalse();
    }
    @Test void validateAndGetNeverConsumeToken() throws Exception {
        var user = signup(); String raw = issue();
        mvc.perform(securePost("/api/auth/password-reset/validate", new MockHttpSession(), "{\"token\":\"" + raw + "\"}"))
                .andExpect(status().isNoContent()).andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get("/api/auth/password-reset/confirm")).andExpect(status().isMethodNotAllowed());
        assertThat(tokens.findByTokenHash(PasswordResetTokens.hash(raw)).orElseThrow().getUsedAt()).isNull();
        assertThat(encoder.matches(PASSWORD, credentials.findByUserId(user.getId()).orElseThrow().getPasswordHash())).isTrue();
        confirm(raw);
    }
    @Test void passwordChangeStoresEncodedPasswordAndOldLoginFailsNewLoginWorksWithoutAutoLogin() throws Exception {
        var user = signup(); String raw = issue(); var session = new MockHttpSession();
        mvc.perform(securePost("/api/auth/password-reset/confirm", session,
                "{\"token\":\"" + raw + "\",\"password\":\"" + NEW_PASSWORD + "\",\"passwordConfirmation\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(session.getAttribute(CurrentUserSession.USER_ID)).isNull();
        var credential = credentials.findByUserId(user.getId()).orElseThrow();
        assertThat(credential.getPasswordHash()).startsWith("{bcrypt}").isNotEqualTo(NEW_PASSWORD);
        assertThat(encoder.matches(NEW_PASSWORD, credential.getPasswordHash())).isTrue();
        assertThat(credential.isEmailVerified()).isFalse(); assertThat(credential.isVerificationRequired()).isTrue();
        reject("INVALID_CREDENTIALS", () -> auth.login(new EmailLoginRequest(email, PASSWORD)));
        var loginSession = new MockHttpSession();
        mvc.perform(securePost("/api/auth/login", loginSession, "{\"email\":\"" + email + "\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.getId()));
        assertThat(loginSession.getAttribute(AuthSessionService.AUTHENTICATION_VERSION)).isEqualTo(1L);
        mvc.perform(get("/api/auth/me").session(loginSession)).andExpect(status().isOk());
        drainMonitoring();
        assertThat(events.findAll()).anyMatch(e -> e.getEventCode() == MonitoringEventCode.PASSWORD_RESET_COMPLETED && user.getId().equals(e.getUserId()));
    }
    @Test void expiresAtExactlyThirtyMinutesAndRejectsMalformedUnknownUsedTokens() throws Exception {
        signup(); String raw = issue(); advance(1799); reset.validate(raw); advance(1);
        reject("PASSWORD_RESET_EXPIRED", () -> confirm(raw));
        reject("PASSWORD_RESET_INVALID", () -> confirm("bad"));
        reject("PASSWORD_RESET_INVALID", () -> confirm(PasswordResetTokens.generate()));
        advance(3601); String fresh = issue(); confirm(fresh);
        reject("PASSWORD_RESET_REUSED", () -> confirm(fresh));
    }
    @Test void concurrentUseHasExactlyOneWinner() throws Exception {
        signup(); String raw = issue(); String encoded = encoder.encode(NEW_PASSWORD);
        var results = concurrent(8, () -> { try { reset.confirmEncoded(raw, encoded); return "OK"; } catch (AuthException e) { return e.getCode(); } });
        assertThat(results).containsOnly("OK", "PASSWORD_RESET_REUSED");
        assertThat(Collections.frequency(results, "OK")).isEqualTo(1);
    }
    @Test void newLinkReplacesOldLinkAndSuccessfulChangeRevokesAllUnusedLinks() throws Exception {
        var user = signup(); String first = issue(); advance(60); String second = issue();
        reject("PASSWORD_RESET_INVALID", () -> confirm(first)); confirm(second);
        var credential = credentials.findByUserId(user.getId()).orElseThrow();
        assertThat(tokens.findByCredentialIdAndUsedAtIsNullAndInvalidatedAtIsNull(credential.getId())).isEmpty();
    }
    @Test void tokenPurposeCannotBeMixedInEitherDirection() throws Exception {
        var user = signup(); String raw = issue();
        reject("EMAIL_VERIFICATION_INVALID", () -> verification.confirm(user.getId(), raw));
        var credential = credentials.findByUserId(user.getId()).orElseThrow(); String verificationRaw = EmailVerificationTokens.generate();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            verificationTokens.findByCredentialIdAndUsedAtIsNullAndInvalidatedAtIsNull(credential.getId()).forEach(t -> t.invalidate(clock.instant()));
            verificationTokens.flush();
            verificationTokens.saveAndFlush(new EmailVerificationToken(credential, EmailVerificationTokens.hash(verificationRaw), clock.instant(), clock.instant().plusSeconds(300)));
        });
        reject("PASSWORD_RESET_INVALID", () -> confirm(verificationRaw));
        assertThat(credentials.findByUserId(user.getId()).orElseThrow().isEmailVerified()).isFalse();
    }
    @Test void targetComesFromTokenEvenWhenAnotherUserIsLoggedInAndExtraUserIdIsSupplied() throws Exception {
        var target = signup(); String raw = issue(); var other = users.saveAndFlush(new User("다른 사용자"));
        var otherSession = loggedIn(other);
        mvc.perform(securePost("/api/auth/password-reset/confirm", otherSession,
                "{\"token\":\"" + raw + "\",\"userId\":" + other.getId() + ",\"password\":\"" + NEW_PASSWORD + "\",\"passwordConfirmation\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(auth.login(new EmailLoginRequest(email, NEW_PASSWORD)).getId()).isEqualTo(target.getId());
        assertThat(credentials.findByUserId(other.getId())).isEmpty();
        mvc.perform(get("/api/auth/me").session(otherSession)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(other.getId()));
    }
    @Test void revokesCurrentAndOtherDeviceSessionsIncludingOtherServerAndLegacySessionsAndPreservesDiscordAndCommunity() throws Exception {
        var user = signup(); String discordId = UUID.randomUUID().toString();
        var link = external.saveAndFlush(new UserExternalAccount(user, ExternalAccountProvider.DISCORD, discordId, "discord"));
        var community = communities.createCommunity("기존 커뮤니티", user.getId());
        var first = loggedIn(user); var second = loggedIn(user);
        var replicaSessions = new AuthSessionService(users); var replicaRequest = new MockHttpServletRequest();
        var replica = new MockHttpSession(); replicaRequest.setSession(replica); replicaSessions.authenticate(replicaRequest, user);
        var legacy = new MockHttpSession(); legacy.setAttribute(CurrentUserSession.USER_ID, user.getId());
        String raw = issue(); confirm(raw);
        assertThat(first.isInvalid()).isTrue(); assertThat(second.isInvalid()).isTrue();
        assertThat(replica.isInvalid()).isFalse();
        assertThatThrownBy(() -> replicaSessions.requireUser(replica)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        mvc.perform(get("/api/auth/me").session(replica)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(legacy)).andExpect(status().isUnauthorized());
        assertThat(external.findById(link.getId()).orElseThrow().getExternalUserId()).isEqualTo(discordId);
        assertThat(memberships.findByCommunityIdAndUserId(community.getId(), user.getId())).isPresent();
        var discordUser = discord.findOrCreateUser(new DiscordApiUser(discordId, "discord", "닉네임", null));
        assertThat(discordUser.getId()).isEqualTo(user.getId());
        var relogin = loggedIn(discordUser); mvc.perform(get("/api/auth/me").session(relogin)).andExpect(status().isOk());
        sessions.revokeBeforeVersion(user.getId(), 1); assertThat(relogin.isInvalid()).isFalse();
    }
    @Test void loginVerifiedBeforeResetCannotPromoteStaleUserAfterReset() throws Exception {
        signup(); User staleLogin = auth.login(new EmailLoginRequest(email, PASSWORD)); String raw = issue(); confirm(raw);
        var request = new MockHttpServletRequest();
        assertThatThrownBy(() -> sessions.authenticate(request, staleLogin)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(request.getSession(false)).isNull();
    }
    @Test void alreadyVerifiedEmailKeepsItsVerificationStateAndVerificationTokenAfterResetRemainsSeparate() throws Exception {
        var user = signup();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> credentials.findByUserId(user.getId()).orElseThrow().verifyEmail());
        String raw = issue(); confirm(raw);
        assertThat(credentials.findByUserId(user.getId()).orElseThrow().isEmailVerified()).isTrue();
    }
    @Test void policyFailureDoesNotConsumeTokenOrChangePassword() throws Exception {
        var user = signup(); String raw = issue();
        for (String password : List.of("short1", "abcdefgh", "12345678", "A1" + "가".repeat(24))) {
            mvc.perform(securePost("/api/auth/password-reset/confirm", new MockHttpSession(),
                    "{\"token\":\"" + raw + "\",\"password\":\"" + password + "\",\"passwordConfirmation\":\"" + password + "\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
        }
        mvc.perform(securePost("/api/auth/password-reset/confirm", new MockHttpSession(),
                "{\"token\":\"" + raw + "\",\"password\":\"" + NEW_PASSWORD + "\",\"passwordConfirmation\":\"Other123!\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
        assertThat(tokens.findByTokenHash(PasswordResetTokens.hash(raw)).orElseThrow().getUsedAt()).isNull();
        assertThat(auth.login(new EmailLoginRequest(email, PASSWORD)).getId()).isEqualTo(user.getId());
    }
    @Test void allPublicMutationsRequireCsrfIncludingEncodedMappedPaths() throws Exception {
        for (String action : List.of("request", "validate", "confirm")) {
            mvc.perform(post("/api/auth/password-reset/" + action).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_MISSING"));
            mvc.perform(post("/api/auth/password-reset/" + action + ";test=x").contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
    }
    @Test void smtpFailureNeverChangesPasswordOrAccountAndManualRetryAfterCooldownRecovers() throws Exception {
        var user = signup(); String originalHash = credentials.findByUserId(user.getId()).orElseThrow().getPasswordHash();
        doThrow(new org.springframework.mail.MailAuthenticationException("private@example.com secret #token=hidden"))
                .when(mail).send(eq(email), anyString());
        reset.issue(email); var credential = credentials.findByUserId(user.getId()).orElseThrow();
        for (int i = 0; i < 250 && tokens.findByCredentialIdAndUsedAtIsNullAndInvalidatedAtIsNull(credential.getId()).size() != 0; i++) Thread.sleep(20);
        assertThat(credentials.findByUserId(user.getId()).orElseThrow().getPasswordHash()).isEqualTo(originalHash);
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isTrue();
        assertThat(tokens.findByCredentialIdAndUsedAtIsNullAndInvalidatedAtIsNull(credential.getId())).isEmpty();
        assertThat(jdbc.queryForObject("select daily_count from password_reset_mail_quota where id=1", Integer.class)).isEqualTo(1);
        reset.issue(email); verify(mail, times(1)).send(eq(email), anyString());
        advance(60); doAnswer(call -> { delivered.put(call.getArgument(0), call.getArgument(1)); return null; }).when(mail).send(eq(email), anyString());
        String fresh = issue(); confirm(fresh);
    }
    @Test void concurrentRequestsCannotBypassAccountCooldown() throws Exception {
        var user = signup(); concurrent(10, () -> { reset.issue(email); return "OK"; }); raw();
        var credential = credentials.findByUserId(user.getId()).orElseThrow();
        assertThat(tokens.findByCredentialIdAndCreatedAtGreaterThanOrderByCreatedAtAscIdAsc(credential.getId(), clock.instant().minusSeconds(86400))).hasSize(1);
        verify(mail, times(1)).send(eq(email), anyString());
    }
    @Test void hourlyDailyLimitsCountFailuresAndDoNotResetOnServiceRestart() throws Exception {
        var user = signup(); issue();
        for (int i = 0; i < 2; i++) { advance(60); issue(); }
        advance(60); reset.issue(email); verify(mail, times(3)).send(eq(email), anyString());
        for (int i = 0; i < 2; i++) { advance(3601); issue(); }
        advance(3601); reset.issue(email); verify(mail, times(5)).send(eq(email), anyString());
        var restarted = new PasswordResetService(users, credentials, tokens, quota,
                resetProperties, publisher, resetEvents, sessions, clock);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> restarted.issue(email));
        // Persistent counts, rather than a service-local account map, are the source of the limit.
        assertThat(tokens.findByCredentialIdAndCreatedAtGreaterThanOrderByCreatedAtAscIdAsc(credentials.findByUserId(user.getId()).orElseThrow().getId(), clock.instant().minusSeconds(86400))).hasSize(5);
        advance(86401); issue();
    }
    @Test void globalBudgetIsAtomicAcrossAccountsAndPersistsAcrossRestarts() throws Exception {
        var user = signup();
        jdbc.update("update password_reset_mail_quota set daily_count=29 where id=1");
        String otherEmail = UUID.randomUUID() + "@example.com";
        auth.register(new SignupRequest(otherEmail, PASSWORD, PASSWORD, "다른 계정"));
        concurrent(2, new Callable<String>() {
            private final java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();
            public String call() { reset.issue(index.getAndIncrement() == 0 ? email : otherEmail); return "OK"; }
        });
        assertThat(jdbc.queryForObject("select daily_count from password_reset_mail_quota where id=1", Integer.class)).isEqualTo(30);
        assertThat(jdbc.queryForObject("select monthly_count from password_reset_mail_quota where id=1", Integer.class)).isEqualTo(1);
        reset.initializeQuota(); assertThat(jdbc.queryForObject("select daily_count from password_reset_mail_quota where id=1", Integer.class)).isEqualTo(30);
    }
    @Test void monthlyBudgetAndDayBoundaryDoNotResetMonthlyCounter() throws Exception {
        signup(); jdbc.update("update password_reset_mail_quota set monthly_count=600 where id=1");
        reset.issue(email); verifyNoInteractions(mail);
        advance(86400); reset.issue(email); verifyNoInteractions(mail);
    }
    @Test void withdrawalOrAnotherPasswordChangeMakesOutstandingTokenUnusable() throws Exception {
        var user = signup(); String raw = issue();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            users.findForUpdate(user.getId()); credentials.findByUserId(user.getId()).orElseThrow().changePasswordHash(encoder.encode("OtherPass123!"));
        });
        reject("PASSWORD_RESET_INVALID", () -> confirm(raw));
        var proof = withdrawals.verifyPassword(user.getId(), "OtherPass123!"); withdrawals.withdraw(user.getId(), proof, true);
        reject("PASSWORD_RESET_INVALID", () -> confirm(raw));
    }
    @Test void rollbackRestoresOldPasswordVersionAndUnconsumedToken() throws Exception {
        var user = signup(); String raw = issue(); String before = credentials.findByUserId(user.getId()).orElseThrow().getPasswordHash();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            reset.confirmEncoded(raw, encoder.encode(NEW_PASSWORD)); throw new IllegalStateException("Simulated DB failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(credentials.findByUserId(user.getId()).orElseThrow().getPasswordHash()).isEqualTo(before);
        assertThat(users.findById(user.getId()).orElseThrow().getAuthenticationVersion()).isZero();
        assertThat(tokens.findByTokenHash(PasswordResetTokens.hash(raw)).orElseThrow().getUsedAt()).isNull();
        confirm(raw);
    }
    @Test void restartLosesQueuedSecretButNewRequestRecoversWithoutChangingPassword() throws Exception {
        var user = signup(); issue(); var credential = credentials.findByUserId(user.getId()).orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> tokens.findByCredentialIdAndUsedAtIsNullAndInvalidatedAtIsNull(credential.getId()).getFirst().delivery(PasswordResetToken.Delivery.QUEUED));
        advance(60); String raw = issue(); confirm(raw);
    }
    @Test void retentionDeletesExpiredHistoryButKeepsBudgetAndCurrentTokens() throws Exception {
        signup(); String first = issue(); advance(86400 * 9); String fresh = issue();
        retention.cleanup(); assertThat(tokens.findByTokenHash(PasswordResetTokens.hash(first))).isEmpty();
        reset.validate(fresh); assertThat(jdbc.queryForObject("select count(*) from password_reset_mail_quota", Long.class)).isEqualTo(1);
    }
    @Test void missingBudgetFailsClosedWithoutTokenMailOrPasswordChange() {
        var user = signup(); jdbc.update("delete from password_reset_mail_quota");
        try {
            assertThatThrownBy(() -> reset.issue(email)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(mail); assertThat(auth.login(new EmailLoginRequest(email, PASSWORD)).getId()).isEqualTo(user.getId());
        } finally { reset.initializeQuota(); }
    }
    @Test void resetEventsContainNoEmailHashPasswordTokenOrLink() throws Exception {
        var user = signup(); String raw = issue(); confirm(raw);
        drainMonitoring();
        var event = events.findAll().stream().filter(e -> e.getEventCode() == MonitoringEventCode.PASSWORD_RESET_COMPLETED && user.getId().equals(e.getUserId())).findFirst().orElseThrow();
        assertThat(event.getMessage()).doesNotContain(email, raw, PASSWORD, NEW_PASSWORD, "#token", "http");
        assertThat(event.getMetadata().toString()).doesNotContain(email, raw, PASSWORD, NEW_PASSWORD, "#token");
    }
    @Test void linkReplacedAfterControllerPreflightCannotBeConsumedFromStaleOsivCache() throws Exception {
        signup(); String first = issue();
        withOpenView(() -> {
            reset.validate(first); // Deliberately retain the token/credential in a request-scoped persistence context.
            advance(60);
            delivered.remove(email);
            try (var pool = Executors.newSingleThreadExecutor()) {
                try { pool.submit(() -> reset.issue(email)).get(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }
            reject("PASSWORD_RESET_INVALID", () -> confirm(first));
        });
        String latest = raw(); confirm(latest);
    }
    @Test void emailVerifiedAfterControllerPreflightIsNotOverwrittenByPasswordUpdate() throws Exception {
        var user = signup(); String raw = issue();
        withOpenView(() -> {
            reset.validate(raw);
            try (var pool = Executors.newSingleThreadExecutor()) {
                try { pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    users.findForUpdate(user.getId()); credentials.findByUserId(user.getId()).orElseThrow().verifyEmail();
                })).get(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }
            confirm(raw);
        });
        assertThat(credentials.findByUserId(user.getId()).orElseThrow().isEmailVerified()).isTrue();
    }
    private void withOpenView(Runnable action) {
        var em = entityManagerFactory.createEntityManager();
        assertThat(TransactionSynchronizationManager.hasResource(entityManagerFactory)).isFalse();
        TransactionSynchronizationManager.bindResource(entityManagerFactory, new org.springframework.orm.jpa.EntityManagerHolder(em));
        try { action.run(); }
        finally { TransactionSynchronizationManager.unbindResource(entityManagerFactory); em.close(); }
    }
    static <T> List<T> concurrent(int count, Callable<T> task) throws Exception {
        try (var pool = Executors.newFixedThreadPool(count)) {
            var start = new CountDownLatch(1); List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) futures.add(pool.submit(() -> { start.await(); return task.call(); }));
            start.countDown(); List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS)); return results;
        }
    }
    static class AdjustableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    }
    @org.springframework.boot.test.context.TestConfiguration static class ClockConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        AdjustableClock resetClock() { return new AdjustableClock(); }
    }
}
