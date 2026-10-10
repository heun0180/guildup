package com.guildup.user.verification;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.repository.*;
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
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:email-verification;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "auth.email-verification.enforce-new-users=true", "auth.email-verification.ip-hourly-limit=1000",
        "auth.email-verification.ip-daily-limit=2000", "auth.email-verification.confirm-per-minute=1000"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(EmailVerificationFlowTests.ClockConfig.class)
class EmailVerificationFlowTests {
    static final String PASSWORD = "GuildUp123!";
    @Autowired UserRepository users;
    @Autowired UserCredentialRepository credentials;
    @Autowired UserExternalAccountRepository external;
    @Autowired EmailVerificationTokenRepository tokens;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean EmailVerificationService verification;
    @Autowired EmailVerificationDeliveryTransactions delivery;
    @Autowired EmailVerificationMailListener listener;
    @Autowired CredentialAuthService auth;
    @Autowired DiscordLoginService discord;
    @Autowired CommunityService communities;
    @Autowired CommunityUserRepository memberships;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MonitoringEventRepository events;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AccountWithdrawalService withdrawals;
    @Autowired EmailVerificationRetention retention;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @MockitoBean EmailVerificationMailService mail;
    @Autowired AdjustableClock clock;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    final AtomicReference<Instant> now = new AtomicReference<>();
    final ConcurrentMap<String, String> delivered = new ConcurrentHashMap<>();
    String email;
    @BeforeEach void setup() throws Exception {
        now.set(Instant.now()); email = UUID.randomUUID() + "@example.com";
        clock.now = now;
        doAnswer(call -> {
            String address = call.getArgument(0), raw = call.getArgument(1);
            // Sender runs on a separate thread only after the credential/token commit is visible.
            assertThat(jdbc.queryForObject("select count(*) from user_credentials where email = ?", Long.class, address)).isEqualTo(1);
            assertThat(tokens.findByTokenHash(EmailVerificationTokens.hash(raw))).isPresent();
            delivered.put(address, raw); return null;
        }).when(mail).send(anyString(), anyString());
    }
    User signup() { return auth.register(new SignupRequest(email, PASSWORD, PASSWORD, "이메일 회원")); }
    String raw() throws Exception {
        for (int i = 0; i < 100 && !delivered.containsKey(email); i++) Thread.sleep(20);
        assertThat(delivered).containsKey(email); return delivered.get(email);
    }
    void advance(long seconds) { now.updateAndGet(value -> value.plusSeconds(seconds)); }
    MockHttpSession session(User user) { var session = new MockHttpSession(); session.setAttribute(CurrentUserSession.USER_ID, user.getId()); return session; }
    String csrf(MockHttpSession session) { return SessionCsrfTokens.getOrCreate(session); }
    void confirm(User user, String raw) { verification.confirm(user.getId(), raw); }
    void rejected(String code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(AuthException.class, e -> assertThat(e.getCode()).isEqualTo(code)); }

    @Test void registrationKeepsIdUnverifiedAndStoresOnlyHashAndMailAfterCommit() throws Exception {
        var user = signup(); String raw = raw();
        var credential = credentials.findByUserId(user.getId()).orElseThrow();
        assertThat(credential.isEmailVerified()).isFalse(); assertThat(credential.isVerificationRequired()).isTrue();
        assertThat(raw).hasSize(43); assertThat(Base64.getUrlDecoder().decode(raw)).hasSize(32);
        assertThat(jdbc.queryForObject("select token_hash from email_verification_tokens where credential_id = ?", String.class, credential.getId()))
                .isEqualTo(EmailVerificationTokens.hash(raw)).doesNotContain(raw);
        var token = tokens.findByTokenHash(EmailVerificationTokens.hash(raw)).orElseThrow();
        assertThat(Duration.between(token.getCreatedAt(), token.getExpiresAt())).isEqualTo(Duration.ofSeconds(300));
        rejected("EMAIL_ALREADY_USED", this::signup);
        assertThat(credentials.findByEmail(email).orElseThrow().getUser().getId()).isEqualTo(user.getId());
    }
    @Test void successfulConfirmationChangesOnlyCredentialAndRejectsReuse() throws Exception {
        var user = signup(); String raw = raw();
        var community = communities.createCommunity("기존 커뮤니티", user.getId());
        long membershipsBefore = memberships.count();
        confirm(user, raw);
        assertThat(credentials.findByUserId(user.getId()).orElseThrow().isEmailVerified()).isTrue();
        assertThat(tokens.findByTokenHash(EmailVerificationTokens.hash(raw)).orElseThrow().getUsedAt()).isNotNull();
        assertThat(memberships.count()).isEqualTo(membershipsBefore);
        assertThat(memberships.findByCommunityIdAndUserId(community.getId(), user.getId())).isPresent();
        rejected("EMAIL_VERIFICATION_USED", () -> confirm(user, raw));
    }
    @Test void expiredMalformedAndUnknownTokensCannotChangeState() throws Exception {
        var user = signup(); String raw = raw(); advance(300);
        rejected("EMAIL_VERIFICATION_EXPIRED", () -> confirm(user, raw));
        rejected("EMAIL_VERIFICATION_INVALID", () -> confirm(user, "bad"));
        rejected("EMAIL_VERIFICATION_INVALID", () -> confirm(user, EmailVerificationTokens.generate()));
        assertThat(credentials.findByUserId(user.getId()).orElseThrow().isEmailVerified()).isFalse();
    }
    @Test void tokenIsStillValidOneSecondBefore300SecondExpiry() throws Exception {
        var user = signup(); String raw = raw(); advance(299);
        confirm(user, raw);
        assertThat(credentials.findByUserId(user.getId()).orElseThrow().isEmailVerified()).isTrue();
    }
    @Test void expiredQueuedMailIsFailedWithoutWaitingForTenMinuteDeliveryTimeout() throws Exception {
        var user = signup(); String raw = raw();
        Long id = tokens.findByTokenHash(EmailVerificationTokens.hash(raw)).orElseThrow().getId();
        for (int i = 0; i < 100 && !verification.status(user.getId()).deliveryStatus().equals("SENT"); i++) Thread.sleep(20);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                tokens.findById(id).orElseThrow().delivery(EmailVerificationToken.Delivery.QUEUED));
        advance(299); assertThat(verification.status(user.getId()).deliveryStatus()).isEqualTo("QUEUED");
        advance(1); assertThat(verification.status(user.getId()).deliveryStatus()).isEqualTo("FAILED");
        assertThat(delivery.begin(id)).isEmpty(); verify(mail, times(1)).send(email, raw);
    }
    @Test void tokenCannotVerifyAnotherUser() throws Exception {
        var owner = signup(); String raw = raw(); var other = users.saveAndFlush(new User("다른 사용자"));
        rejected("EMAIL_VERIFICATION_INVALID", () -> confirm(other, raw));
        assertThat(credentials.findByUserId(owner.getId()).orElseThrow().isEmailVerified()).isFalse();
    }
    @Test void simultaneousConfirmationHasExactlyOneWinner() throws Exception {
        var user = signup(); String raw = raw();
        var results = concurrent(6, () -> { try { confirm(user, raw); return "OK"; } catch (AuthException e) { return e.getCode(); } });
        assertThat(results).containsOnly("OK", "EMAIL_VERIFICATION_USED"); assertThat(Collections.frequency(results, "OK")).isEqualTo(1);
    }
    @Test void resendWaits60SecondsAndImmediatelyReplacesOldLink() throws Exception {
        var user = signup(); String first = raw();
        rejected("EMAIL_VERIFICATION_RATE_LIMITED", () -> verification.resend(user.getId()));
        advance(60); delivered.clear(); verification.resend(user.getId()); String second = raw();
        assertThat(second).isNotEqualTo(first);
        rejected("EMAIL_VERIFICATION_REPLACED", () -> confirm(user, first)); confirm(user, second);
    }
    @Test void hourlyLimitIncludesInitialMailAndResetsOnRollingBoundary() throws Exception {
        var user = signup(); raw();
        for (int i = 0; i < 2; i++) { advance(60); verification.resend(user.getId()); }
        advance(60); rejected("EMAIL_VERIFICATION_RATE_LIMITED", () -> verification.resend(user.getId()));
        advance(3600); verification.resend(user.getId());
    }
    @Test void dailyLimitPersistsAcrossServiceRecreationAndCountsFailures() throws Exception {
        var user = signup(); raw();
        for (int i = 0; i < 4; i++) { advance(3601); verification.resend(user.getId()); }
        advance(3601); rejected("EMAIL_VERIFICATION_RATE_LIMITED", () -> verification.resend(user.getId()));
        assertThat(verification.status(user.getId()).retryAfterSeconds()).isPositive();
        advance(86401); verification.resend(user.getId());
    }
    @Test void verifiedAccountDoesNotSendAgainAndConcurrentResendCannotReopenIt() throws Exception {
        var user = signup(); String raw = raw(); advance(60); confirm(user, raw);
        long before = tokens.count();
        assertThat(verification.resend(user.getId()).emailVerified()).isTrue(); assertThat(tokens.count()).isEqualTo(before);
    }
    @Test void simultaneousResendsOnlyIssueOneToken() throws Exception {
        var user = signup(); raw(); advance(60); long before = tokens.count();
        var results = concurrent(5, () -> { try { verification.resend(user.getId()); return "OK"; } catch (AuthException e) { return e.getCode(); } });
        assertThat(Collections.frequency(results, "OK")).isEqualTo(1); assertThat(tokens.count()).isEqualTo(before + 1);
    }
    @Test void confirmationAndResendRaceNeverReopensVerifiedCredential() throws Exception {
        var user = signup(); String raw = raw(); advance(60);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var confirmed = pool.submit(() -> { start.await(); try { confirm(user, raw); return true; } catch (AuthException e) { assertThat(e.getCode()).isEqualTo("EMAIL_VERIFICATION_REPLACED"); return false; } });
            var resent = pool.submit(() -> { start.await(); return verification.resend(user.getId()); });
            start.countDown(); boolean success = confirmed.get(10, TimeUnit.SECONDS); resent.get(10, TimeUnit.SECONDS);
            assertThat(credentials.findByUserId(user.getId()).orElseThrow().isEmailVerified()).isEqualTo(success);
            if (success) assertThat(verification.resend(user.getId()).emailVerified()).isTrue();
        }
    }
    @Test void smtpDelayCannotHoldRegistrationTransactionOrBlockLogin() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return null; }).when(mail).send(anyString(), anyString());
        try {
            var user = signup(); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(auth.login(new EmailLoginRequest(email, PASSWORD)).getId()).isEqualTo(user.getId());
            assertThat(credentials.findByUserId(user.getId())).isPresent();
        } finally { release.countDown(); }
    }
    @Test void smtpFailureKeepsAccountAndAllowsRetryAndNeverRecordsSensitiveContent() throws Exception {
        doThrow(new IllegalStateException("private@example.com #token=do-not-log")).when(mail).send(anyString(), anyString());
        var user = signup();
        for (int i = 0; i < 100 && !verification.status(user.getId()).deliveryStatus().equals("FAILED"); i++) Thread.sleep(20);
        assertThat(verification.status(user.getId()).deliveryStatus()).isEqualTo("FAILED");
        assertThat(auth.login(new EmailLoginRequest(email, PASSWORD)).getId()).isEqualTo(user.getId());
        advance(60); doNothing().when(mail).send(anyString(), anyString()); verification.resend(user.getId());
        assertThat(events.findAll().stream().filter(e -> e.getEventCode() == MonitoringEventCode.EMAIL_VERIFICATION_MAIL_FAILED)).isNotEmpty()
                .allSatisfy(e -> assertThat(e.getMessage() + e.getMetadata()).doesNotContain(email, "private@example.com", "do-not-log"));
    }
    @Test void duplicateMailEventDoesNotDeliverTwice() throws Exception {
        var user = signup(); String raw = raw();
        Long id = tokens.findByTokenHash(EmailVerificationTokens.hash(raw)).orElseThrow().getId();
        listener.committed(new EmailVerificationService.MailRequested(id, raw));
        assertThat(delivery.begin(id)).isEmpty(); verify(mail, timeout(1000).times(1)).send(email, raw);
    }
    @Test void tokenPersistenceDoesNotDependOnProcessMemory() throws Exception {
        var user = signup(); String raw = raw(); delivered.clear();
        confirm(user, raw); assertThat(credentials.findByUserId(user.getId()).orElseThrow().isEmailVerified()).isTrue();
    }
    @Test void transactionRollbackNeverSendsOrLeavesToken() throws Exception {
        long before = tokens.count();
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            var user = users.save(new User("롤백"));
            var credential = credentials.saveAndFlush(new UserCredential(user, email, "unused"));
            verification.issueInitial(credential); status.setRollbackOnly();
        });
        assertThat(tokens.count()).isEqualTo(before); verifyNoInteractions(mail);
    }
    @Test void failedTokenPersistenceRollsBackEntireRegistrationAndDoesNotSend() {
        long userCount = users.count(), credentialCount = credentials.count(), tokenCount = tokens.count();
        EmailVerificationService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(verification);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test-only-db-failure")).when(target).issueInitial(any());
        assertThatThrownBy(this::signup).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(users.count()).isEqualTo(userCount); assertThat(credentials.count()).isEqualTo(credentialCount);
        assertThat(tokens.count()).isEqualTo(tokenCount); verifyNoInteractions(mail);
    }
    @Test void credentialsDeletedDuringWithdrawalCascadeTokensAndRejectPendingLink() throws Exception {
        var user = signup(); String raw = raw();
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            var locked = users.findForUpdate(user.getId()).orElseThrow();
            credentials.deleteAll(credentials.findByUserId(user.getId()).stream().toList()); locked.withdraw(now.get()); users.flush();
        });
        assertThat(tokens.findByTokenHash(EmailVerificationTokens.hash(raw))).isEmpty();
        rejected("EMAIL_VERIFICATION_INVALID", () -> confirm(user, raw));
    }
    @Test void actualWithdrawalAndConfirmationRacePreserveWithdrawalAndInvalidateAllLinks() throws Exception {
        var user = signup(); String raw = raw(); var proof = withdrawals.verifyPassword(user.getId(), PASSWORD);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var confirm = pool.submit(() -> { start.await(); try { verification.confirm(user.getId(), raw); } catch (AuthException e) { assertThat(e.getCode()).isIn("EMAIL_VERIFICATION_INVALID", "LOGIN_REQUIRED"); } return true; });
            var withdraw = pool.submit(() -> { start.await(); withdrawals.withdraw(user.getId(), proof, true); return true; });
            start.countDown(); confirm.get(10, TimeUnit.SECONDS); withdraw.get(10, TimeUnit.SECONDS);
            assertThat(users.findById(user.getId()).orElseThrow().isActive()).isFalse();
            assertThat(credentials.findByUserId(user.getId())).isEmpty();
            assertThat(tokens.findByTokenHash(EmailVerificationTokens.hash(raw))).isEmpty();
        }
    }
    @Test void expiredRecordsAreCleanedAfterRetentionWithoutTouchingCredentials() throws Exception {
        var user = signup(); String raw = raw();
        jdbc.update("update email_verification_tokens set created_at=?,expires_at=? where token_hash=?",
                java.sql.Timestamp.from(now.get().minus(Duration.ofDays(10))), java.sql.Timestamp.from(now.get().minus(Duration.ofDays(9))), EmailVerificationTokens.hash(raw));
        retention.cleanup();
        assertThat(tokens.findByTokenHash(EmailVerificationTokens.hash(raw))).isEmpty();
        assertThat(credentials.findByUserId(user.getId())).isPresent();
    }
    @Test void csrfAndLoginAndGetPolicyProtectServerState() throws Exception {
        var user = signup(); String raw = raw(); var session = session(user);
        mvc.perform(get("/api/auth/email-verification").session(session).param("token", raw)).andExpect(status().isOk());
        assertThat(credentials.findByUserId(user.getId()).orElseThrow().isEmailVerified()).isFalse();
        mvc.perform(get("/api/auth/email-verification/confirm").session(session).param("token", raw)).andExpect(status().isMethodNotAllowed());
        for (String endpoint : List.of("confirm", "resend")) {
            mvc.perform(post("/api/auth/email-verification/" + endpoint).session(session).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_MISSING"));
            mvc.perform(post("/api/auth/email-verification/" + endpoint).session(session).header("X-CSRF-Token", "wrong-token")
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
            mvc.perform(post("/api/auth/email-verification/" + endpoint).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/email-verification/confirm").session(session).header("X-CSRF-Token", csrf(session))
                        .contentType("application/json").content("{\"token\":\"" + raw + "\"}"))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void clientSuppliedVerifiedFlagCannotChangeServerCredentialState() throws Exception {
        var anonymous = new MockHttpSession();
        mvc.perform(post("/api/auth/signup").session(anonymous).header("X-CSRF-Token", csrf(anonymous)).contentType("application/json")
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"passwordConfirmation\":\"" + PASSWORD
                                + "\",\"nickname\":\"test\",\"emailVerified\":true,\"verificationRequired\":false}"))
                .andExpect(status().isCreated());
        var credential = credentials.findByEmail(email).orElseThrow();
        assertThat(credential.isEmailVerified()).isFalse(); assertThat(credential.isVerificationRequired()).isTrue();
        mvc.perform(patch("/api/account/profile").session(anonymous).header("X-CSRF-Token", csrf(anonymous)).contentType("application/json")
                        .content("{\"nickname\":\"test\",\"emailVerified\":true}"))
                .andExpect(status().isOk());
        assertThat(credentials.findByEmail(email).orElseThrow().isEmailVerified()).isFalse();
    }
    @Test void resendNeverAcceptsAnExternalEmailAndAnonymousResponsesDoNotRevealAccounts() throws Exception {
        var user = signup(); raw(); advance(60); var session = session(user);
        mvc.perform(post("/api/auth/email-verification/resend").session(session).header("X-CSRF-Token", csrf(session))
                .contentType("application/json").content("{\"email\":\"victim@example.com\"}"))
                .andExpect(status().isAccepted());
        verify(mail, timeout(1000).times(2)).send(eq(email), anyString()); verify(mail, never()).send(eq("victim@example.com"), anyString());
        for (String address : List.of(email, "missing@example.com"))
            mvc.perform(post("/api/auth/email-verification/resend").contentType("application/json").content("{\"email\":\"" + address + "\"}"))
                    .andExpect(status().isUnauthorized());
    }
    @Test void newEmailUsersRestrictedButLoginAccountSupportLogoutRemainAvailable() throws Exception {
        var user = signup(); String raw = raw(); var session = session(user);
        mvc.perform(post("/api/communities").session(session).header("X-CSRF-Token", csrf(session)).contentType("application/json")
                        .content("{\"name\":\"차단 확인\",\"gameType\":\"BATTLEGROUNDS_KAKAO\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_REQUIRED"));
        mvc.perform(get("/api/auth/account").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.emailServiceRestricted").value(true));
        assertThat(auth.login(new EmailLoginRequest(email, PASSWORD)).getId()).isEqualTo(user.getId());
        confirm(user, raw);
        mvc.perform(get("/api/community-discoveries/discord").session(session)).andExpect(status().isOk());
    }
    @Test void legacyUnverifiedEmailAndDiscordOnlyAccountsKeepAccessAndIds() throws Exception {
        var legacy = users.saveAndFlush(new User("기존 이메일"));
        credentials.saveAndFlush(new UserCredential(legacy, email, encoder.encode(PASSWORD)));
        assertThat(auth.login(new EmailLoginRequest(email, PASSWORD)).getId()).isEqualTo(legacy.getId());
        mvc.perform(get("/api/community-discoveries/discord").session(session(legacy))).andExpect(status().isOk());
        assertThat(credentials.findByUserId(legacy.getId()).orElseThrow().isEmailVerified()).isFalse();
        var discordUser = discord.findOrCreateUser(new DiscordApiUser(UUID.randomUUID().toString(), "discord", "기존 Discord", null));
        assertThat(discord.findOrCreateUser(new DiscordApiUser(external.findByUserId(discordUser.getId()).getFirst().getExternalUserId(), "discord", "기존 Discord", null)).getId()).isEqualTo(discordUser.getId());
        mvc.perform(get("/api/auth/email-verification").session(session(discordUser))).andExpect(status().isOk()).andExpect(jsonPath("$.hasEmailCredential").value(false));
    }
    @Test void discordEmailAdditionKeepsExistingUserMembershipAndDiscordAccess() throws Exception {
        var user = discord.findOrCreateUser(new DiscordApiUser(UUID.randomUUID().toString(), "discord", "Discord", null));
        var community = communities.createCommunity("Discord 기록", user.getId()); long count = users.count();
        assertThat(auth.addCredential(user.getId(), new CredentialRequest(email, PASSWORD, PASSWORD)).getId()).isEqualTo(user.getId()); String raw = raw();
        assertThat(users.count()).isEqualTo(count); assertThat(external.findByUserIdAndProvider(user.getId(), ExternalAccountProvider.DISCORD)).isPresent();
        assertThat(memberships.findByCommunityIdAndUserId(community.getId(), user.getId())).isPresent();
        mvc.perform(get("/api/auth/account").session(session(user))).andExpect(status().isOk()).andExpect(jsonPath("$.emailServiceRestricted").value(false));
        confirm(user, raw); assertThat(credentials.findByUserId(user.getId()).orElseThrow().isEmailVerified()).isTrue();
    }
    @Test void monitoringIncludesSuccessExpiredInvalidAndResendLimitWithoutSecrets() throws Exception {
        var user = signup(); String raw = raw(); rejected("EMAIL_VERIFICATION_RATE_LIMITED", () -> verification.resend(user.getId()));
        rejected("EMAIL_VERIFICATION_INVALID", () -> confirm(user, "secret-invalid")); confirm(user, raw);
        var relevant = events.findAll().stream().filter(e -> user.getId().equals(e.getUserId())).toList();
        assertThat(relevant.stream().map(MonitoringEvent::getEventCode)).contains(MonitoringEventCode.EMAIL_VERIFICATION_COMPLETED,
                MonitoringEventCode.EMAIL_VERIFICATION_INVALID, MonitoringEventCode.EMAIL_VERIFICATION_RATE_LIMITED);
        assertThat(relevant).allSatisfy(e -> assertThat(e.getMessage() + e.getMetadata()).doesNotContain(email, raw, PASSWORD, "secret-invalid"));
    }
    private <T> List<T> concurrent(int count, Callable<T> task) throws Exception {
        try (var executor = Executors.newFixedThreadPool(count)) {
            var start = new CountDownLatch(1); List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) futures.add(executor.submit(() -> { start.await(); return task.call(); })); start.countDown();
            var results = new ArrayList<T>(); for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS)); return results;
        }
    }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        AdjustableClock verificationTestClock() { return new AdjustableClock(); }
    }
    static class AdjustableClock extends Clock {
        volatile AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
