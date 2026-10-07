package com.guildup.user.auth;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.bingo.domain.*;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.discord.domain.DiscordVoiceSession;
import com.guildup.discord.oauth.client.DiscordApiClient;
import com.guildup.discord.oauth.client.dto.*;
import com.guildup.killcompetition.domain.*;
import com.guildup.pubg.model.PubgPlatform;
import com.guildup.user.auth.dto.*;
import com.guildup.user.auth.service.*;
import com.guildup.user.domain.*;
import com.guildup.user.repository.*;
import jakarta.persistence.EntityManager;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 DB FK/트랜잭션, 모든 인증수단, 세션/CSRF, 과거 API 결과를 함께 검증한다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:withdrawal;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "discord.oauth.client-id=test-client", "discord.oauth.client-secret=test-secret",
        "discord.oauth.redirect-uri=http://localhost/api/discord/oauth/callback"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(AccountWithdrawalTests.ClockConfig.class)
class AccountWithdrawalTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired UserCredentialRepository credentials;
    @Autowired UserExternalAccountRepository accounts;
    @Autowired CredentialAuthService emailAuth;
    @Autowired DiscordLoginService discordAuth;
    @Autowired AccountWithdrawalService withdrawal;
    @Autowired AuthSessionService authSessions;
    @Autowired com.guildup.discord.oauth.store.DiscordOAuthSessionStore oauthResults;
    @Autowired CommunityUserRepository memberships;
    @Autowired CommunityMemberRepository members;
    @Autowired CommunityMemberAccountRepository memberAccounts;
    @Autowired CommunityRepository communities;
    @Autowired CommunityGameRepository games;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager txManager;
    @MockitoBean DiscordApiClient discord;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    final ObjectMapper json = new ObjectMapper();
    static final String PASSWORD = "GuildUp123!";
    static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    String email;
    TransactionTemplate tx;

    @BeforeEach void setup() {
        email = UUID.randomUUID() + "@example.com";
        tx = new TransactionTemplate(txManager);
    }

    @ParameterizedTest @EnumSource(value = CommunityUserRole.class, names = {"MEMBER", "ADMIN"})
    void memberAndAdminWithdrawalCleansLoginMethodsAndEndsMembershipWithoutDeletingClanRecords(CommunityUserRole role) throws Exception {
        User user = emailUser();
        user.updateProfile(user.getNickname(), LocalDate.of(1993, 1, 1));
        users.saveAndFlush(user);
        String discordId = "discord-" + user.getId();
        accounts.save(new UserExternalAccount(user, ExternalAccountProvider.DISCORD, discordId, "private-discord"));
        var result = oauthResults.saveResult(null, new com.guildup.discord.oauth.dto.DiscordOAuthResultResponse(
                new com.guildup.discord.oauth.dto.DiscordOAuthUserResponse(discordId, "private", "private", null), List.of()));
        Fixture f = fixture(user, role);
        MockHttpSession session = session(user), other = session(user);
        verifyPassword(session, PASSWORD).andExpect(status().isNoContent());
        remove(session, true).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        assertThat(users.findById(user.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(users.findById(user.getId()).orElseThrow().getNickname()).isEqualTo(User.WITHDRAWN_NAME);
        assertThat(users.findById(user.getId()).orElseThrow().getBirthDate()).isNull();
        assertThat(users.findById(user.getId()).orElseThrow().getWithdrawnAt()).isEqualTo(NOW);
        assertThat(credentials.findByUserId(user.getId())).isEmpty();
        assertThat(accounts.findByUserId(user.getId())).isEmpty();
        assertThatThrownBy(() -> oauthResults.getResult(null, result)).isInstanceOf(com.guildup.discord.oauth.exception.DiscordOAuthResultNotFoundException.class);
        assertThat(memberships.findByUserIdOrderByCommunityIdAsc(user.getId())).isEmpty();
        assertThat(memberships.countByCommunityId(f.communityId)).isEqualTo(1);
        tx.executeWithoutResult(ignored -> {
            var retained = memberships.findById(f.membershipId).orElseThrow();
            assertThat(retained.getEndedAt()).isEqualTo(NOW);
            assertThat(retained.getCommunityMember()).isNull();
            assertThat(members.findById(f.memberId).orElseThrow().getStatus()).isEqualTo(CommunityMemberStatus.ACTIVE);
        });
        mvc.perform(get("/api/auth/me").session(other)).andExpect(status().isUnauthorized());
        assertThat(other.isInvalid()).isTrue();
        mvc.perform(get("/api/communities/" + f.communityId).session(session(user))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        // 재요청도 안전한 401이며 500이나 추가 삭제가 아니다.
        remove(session(user), true).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(session(f.owner))).andExpect(status().isOk());
        mvc.perform(get("/api/communities/" + f.communityId).session(session(f.owner))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from monitoring_events where event_code='USER_WITHDRAWN' and user_id=?", Long.class, user.getId())).isEqualTo(1);
    }

    @Test void checksEveryOwnerCommunityAndFinalRequestRechecksEvenAfterEarlierSuccessfulCheck() throws Exception {
        User user = emailUser();
        MockHttpSession session = session(user);
        mvc.perform(get("/api/account/withdrawal/check").session(session)).andExpect(jsonPath("$.canWithdraw").value(true));
        var first = fixture(user, CommunityUserRole.OWNER);
        var second = fixture(user, CommunityUserRole.OWNER);
        mvc.perform(get("/api/account/withdrawal/check").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ownedCommunities.length()").value(2)).andExpect(jsonPath("$.canWithdraw").value(false));
        verifyPassword(session, PASSWORD).andExpect(status().isNoContent());
        remove(session, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMMUNITY_OWNERSHIP_REQUIRED"))
                .andExpect(jsonPath("$.ownedCommunities.length()").value(2));
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isTrue();
        assertThat(credentials.findByUserId(user.getId())).isPresent();
        // 소유권이 정리된 뒤 모든 커뮤니티를 다시 검사한다.
        tx.executeWithoutResult(ignored -> {
            memberships.findById(first.membershipId).orElseThrow().changeRole(CommunityUserRole.MEMBER);
            memberships.findByCommunityIdAndUserId(first.communityId, first.owner.getId()).orElseThrow().changeRole(CommunityUserRole.OWNER);
        });
        remove(session, true).andExpect(status().isConflict()).andExpect(jsonPath("$.ownedCommunities.length()").value(1));
        mvc.perform(delete("/api/communities/" + second.communityId).session(session).header(SessionCsrfTokens.HEADER, token(session)))
                .andExpect(status().isNoContent());
        remove(session, true).andExpect(status().isNoContent());
    }

    @Test void wrongPasswordMissingProofAndMissingFinalAcknowledgementNeverChangeData() throws Exception {
        User user = emailUser(); var session = session(user);
        remove(session, true).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WITHDRAWAL_VERIFICATION_REQUIRED"));
        verifyPassword(session, "Wrong123!").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
        remove(session, true).andExpect(status().isForbidden());
        verifyPassword(session, PASSWORD).andExpect(status().isNoContent());
        remove(session, false).andExpect(status().isBadRequest());
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isTrue();
        assertThat(session.isInvalid()).isFalse();
    }

    @Test void csrfProtectsVerificationAndDeletion() throws Exception {
        var session = session(emailUser());
        mvc.perform(post("/api/account/withdrawal/verify").session(session).contentType("application/json")
                .content("{\"password\":\"" + PASSWORD + "\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_MISSING"));
        verifyPassword(session, PASSWORD).andExpect(status().isNoContent());
        mvc.perform(delete("/api/account").session(session).contentType("application/json").content("{\"acknowledged\":true}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_MISSING"));
        mvc.perform(delete("/api/account").session(session).header(SessionCsrfTokens.HEADER, "invalid")
                .contentType("application/json").content("{\"acknowledged\":true}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test void verificationExpiresAndCannotBeUsedFromAnotherSessionOrUser() throws Exception {
        User user = emailUser(); var session = session(user);
        verifyPassword(session, PASSWORD).andExpect(status().isNoContent());
        remove(session(user), true).andExpect(status().isForbidden());
        var proof = (WithdrawalVerification) session.getAttribute(WithdrawalVerification.ATTRIBUTE);
        var other = session(emailUser()); other.setAttribute(WithdrawalVerification.ATTRIBUTE, proof);
        remove(other, true).andExpect(status().isForbidden());
        session.setAttribute(WithdrawalVerification.ATTRIBUTE,
                new WithdrawalVerification(user.getId(), proof.method(), proof.loginMethodId(), NOW.minusSeconds(300)));
        remove(session, true).andExpect(status().isForbidden());
    }

    @Test void failedVerificationClearsPreviouslyGrantedProof() throws Exception {
        var session = session(emailUser());
        verifyPassword(session, PASSWORD).andExpect(status().isNoContent());
        verifyPassword(session, "wrong").andExpect(status().isBadRequest());
        remove(session, true).andExpect(status().isForbidden());
    }

    @Test void discordOnlyUserNeedsFreshBoundOAuthAndMatchingIdentity() throws Exception {
        User user = discordUser(); var session = session(user);
        remove(session, true).andExpect(status().isForbidden());
        String location = startDiscord(session);
        assertThat(location).contains("prompt=consent", "scope=identify");
        var attempt = (DiscordAuthAttempt) session.getAttribute(DiscordAuthAttempt.ATTRIBUTE);
        assertThat(attempt.purpose()).isEqualTo(DiscordAuthAttempt.Purpose.WITHDRAWAL);
        when(discord.exchangeCode("wrong", attempt.redirectUri())).thenReturn(new DiscordAccessTokenResponse("token", "Bearer", 300, "identify"));
        when(discord.getCurrentUser("token")).thenReturn(new DiscordApiUser("another-id", "other", "other", null));
        mvc.perform(get("/api/auth/discord/callback").session(session).param("state", attempt.state()).param("code", "wrong"))
                .andExpect(redirectedUrl("/account.html?oauthError=DISCORD_IDENTITY_MISMATCH"));
        remove(session, true).andExpect(status().isForbidden());
        startDiscord(session);
        attempt = (DiscordAuthAttempt) session.getAttribute(DiscordAuthAttempt.ATTRIBUTE);
        String oldSessionId = session.getId();
        when(discord.exchangeCode("correct", attempt.redirectUri())).thenReturn(new DiscordAccessTokenResponse("right-token", "Bearer", 300, "identify"));
        when(discord.getCurrentUser("right-token")).thenReturn(identity(user));
        mvc.perform(get("/api/auth/discord/callback").session(session).param("state", attempt.state()).param("code", "correct"))
                .andExpect(redirectedUrl("/account.html?withdrawalVerified=true"));
        assertThat(session.getId()).isEqualTo(oldSessionId);
        mvc.perform(get("/api/account/withdrawal/check").session(session)).andExpect(jsonPath("$.verified").value(true));
        remove(session, true).andExpect(status().isNoContent());
    }

    @Test void discordOAuthStateCannotBeReplayedAndEmailUserCannotBypassPasswordUsingDiscord() throws Exception {
        var user = discordUser(); var session = session(user); startDiscord(session);
        var attempt = (DiscordAuthAttempt) session.getAttribute(DiscordAuthAttempt.ATTRIBUTE);
        mvc.perform(get("/api/auth/discord/callback").session(session).param("state", "incorrect").param("code", "unused"))
                .andExpect(redirectedUrl("/account.html?oauthError=session"));
        mvc.perform(get("/api/auth/discord/callback").session(session).param("state", attempt.state()).param("code", "unused"))
                .andExpect(redirectedUrl("/login.html?oauthError=session"));
        assertThat(session.getAttribute(WithdrawalVerification.ATTRIBUTE)).isNull();
        var emailUser = emailUser(); var passwordSession = session(emailUser);
        mvc.perform(post("/api/auth/discord/withdrawal").session(passwordSession).header(SessionCsrfTokens.HEADER, token(passwordSession)))
                .andExpect(status().isForbidden());
    }

    @Test void sameEmailAndDiscordCanRegisterNewUsersWithoutRestoringMemberships() throws Exception {
        var user = emailUser(); var f = fixture(user, CommunityUserRole.MEMBER);
        accounts.save(new UserExternalAccount(user, ExternalAccountProvider.DISCORD, "id-" + user.getId(), "private-discord"));
        var identity = identity(user);
        var session = session(user); verifyPassword(session, PASSWORD); remove(session, true).andExpect(status().isNoContent());
        assertThatThrownBy(() -> emailAuth.login(new EmailLoginRequest(email, PASSWORD))).isInstanceOf(com.guildup.user.auth.exception.AuthException.class);
        var newEmail = emailAuth.register(new SignupRequest(email, PASSWORD, PASSWORD, "재가입"));
        assertThat(newEmail.getId()).isNotEqualTo(user.getId());
        var newDiscord = discordAuth.findOrCreateUser(identity);
        assertThat(newDiscord.getId()).isNotIn(user.getId(), newEmail.getId());
        assertThat(discordAuth.findOrCreateUser(identity).getId()).isEqualTo(newDiscord.getId());
        assertThat(memberships.findByUserIdOrderByCommunityIdAsc(newEmail.getId())).isEmpty();
        assertThat(memberships.findByUserIdOrderByCommunityIdAsc(newDiscord.getId())).isEmpty();
        assertThat(members.findById(f.memberId)).isPresent();
    }

    @Test void defensiveLoginChecksRejectWithdrawnUsersEvenIfOldLoginRowsUnexpectedlyRemain() {
        var user = emailUser();
        accounts.save(new UserExternalAccount(user, ExternalAccountProvider.DISCORD, "id-" + user.getId(), "private"));
        var identity = identity(user);
        user.withdraw(NOW); users.saveAndFlush(user);
        assertThatThrownBy(() -> emailAuth.login(new EmailLoginRequest(email, PASSWORD))).isInstanceOf(com.guildup.user.auth.exception.AuthException.class);
        assertThatThrownBy(() -> discordAuth.findOrCreateUser(identity)).isInstanceOf(com.guildup.user.auth.exception.AuthException.class);
        assertThatThrownBy(() -> emailAuth.addCredential(user.getId(), new CredentialRequest("other-" + email, PASSWORD, PASSWORD)))
                .isInstanceOf(com.guildup.user.auth.exception.AuthException.class);
    }

    @Test void concurrentWithdrawalFromTwoSessionsHasOneSuccessAndOneUnauthorizedResponse() throws Exception {
        User user = emailUser(); var first = session(user); var second = session(user);
        verifyPassword(first, PASSWORD); verifyPassword(second, PASSWORD);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var a = pool.submit(() -> { start.await(); return remove(first, true).andReturn().getResponse().getStatus(); });
            var b = pool.submit(() -> { start.await(); return remove(second, true).andReturn().getResponse().getStatus(); });
            start.countDown();
            assertThat(List.of(a.get(15, java.util.concurrent.TimeUnit.SECONDS), b.get(15, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(204, 401);
            assertThat(credentials.findByUserId(user.getId())).isEmpty();
        } finally { pool.shutdownNow(); }
    }

    @Test void withdrawalKeepsCompletedBingoKillCompetitionTeamResultsRankingAttendanceBoardAndVoiceHistory() throws Exception {
        var user = emailUser(); var f = fixture(user, CommunityUserRole.ADMIN);
        var records = history(user, f); var ownerSession = session(f.owner);
        String base = "/api/communities/" + f.communityId + "/games/" + f.gameId;
        var bingoBefore = getJson(base + "/bingos/" + records[0], ownerSession);
        var killBefore = getJson(base + "/kill-competitions/" + records[1], ownerSession);
        var rankingBefore = getJson("/api/communities/" + f.communityId + "/rankings", ownerSession);
        var voiceBefore = getJson("/api/communities/" + f.communityId + "/discord/voice-activity", ownerSession);
        var session = session(user); verifyPassword(session, PASSWORD); remove(session, true).andExpect(status().isNoContent());
        var bingoAfter = getJson(base + "/bingos/" + records[0], ownerSession);
        assertThat(bingoAfter.get("participants").get(0).get("nickname").asText()).isEqualTo(User.WITHDRAWN_NAME);
        for (String field : List.of("lineCount", "completedCells", "targetLinesCompletedAt", "blackoutCompletedAt"))
            assertThat(bingoAfter.get("participants").get(0).get(field)).isEqualTo(bingoBefore.get("participants").get(0).get(field));
        var killAfter = getJson(base + "/kill-competitions/" + records[1], ownerSession);
        assertThat(killAfter.get("teams")).isEqualTo(killBefore.get("teams"));
        for (String field : List.of("finalKills", "finalMatchCount", "finalPoints", "teamId"))
            assertThat(killAfter.get("participants").get(0).get(field)).isEqualTo(killBefore.get("participants").get(0).get(field));
        assertThat(killAfter.toString()).contains(User.WITHDRAWN_NAME).doesNotContain("private-pubg", "private-user");
        var rankingAfter = getJson("/api/communities/" + f.communityId + "/rankings", ownerSession);
        assertThat(rankingAfter.get("rankings").get(0).get("score")).isEqualTo(rankingBefore.get("rankings").get(0).get("score"));
        assertThat(rankingAfter.toString()).contains(User.WITHDRAWN_NAME);
        var voiceAfter = getJson("/api/communities/" + f.communityId + "/discord/voice-activity", ownerSession);
        assertThat(voiceAfter.get(0).get("totalSeconds")).isEqualTo(voiceBefore.get(0).get("totalSeconds"));
        assertThat(voiceAfter.get(0).get("nickname").asText()).isEqualTo(User.WITHDRAWN_NAME);
        assertThat(voiceAfter.get(0).get("discordUserId").isNull()).isTrue();
        getJson("/api/communities/" + f.communityId + "/posts/" + records[2], ownerSession);
        assertThat(jdbc.queryForObject("select current_value from pubg_bingo_progress where participant_id=?", BigDecimal.class, records[3])).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select count(*) from pubg_bingo_line_completions where participant_id=?", Long.class, records[3])).isEqualTo(1);
        assertThat(jdbc.queryForObject("select total_points from pubg_kill_competition_match_results where competition_id=?", Integer.class, records[1])).isEqualTo(15);
        assertThat(jdbc.queryForObject("select count(*) from community_attendances where community_member_id=?", Long.class, f.memberId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select discord_user_id from discord_voice_sessions where id=?", String.class, records[4])).isEqualTo("withdrawn-member-" + f.memberId);
        assertThat(jdbc.queryForObject("select left_at from discord_voice_sessions where id=?", java.sql.Timestamp.class, records[4]).toInstant()).isEqualTo(NOW.minusSeconds(60));
        assertThat(memberAccounts.findByCommunityMemberId(f.memberId)).hasSize(2);
        mvc.perform(get("/api/communities/" + f.communityId + "/members").session(ownerSession)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-discord"))));
        // 재가입자의 Discord 연결은 익명 클랜원의 과거 기록과 일치하지 않는다.
        assertThat(memberAccounts.findByCommunityIdAndProviderAndExternalUserId(f.communityId, ExternalAccountProvider.DISCORD, "id-" + user.getId())).isEmpty();
    }

    @Test void databaseFailureRollsBackAllCleanupAndPreservesSession() throws Exception {
        var user = emailUser(); var f = fixture(user, CommunityUserRole.MEMBER); var session = session(user);
        accounts.save(new UserExternalAccount(user, ExternalAccountProvider.DISCORD, "id-" + user.getId(), "private"));
        verifyPassword(session, PASSWORD);
        String rule = "withdrawal_failure_" + user.getId();
        jdbc.execute("alter table users add constraint " + rule + " check (id <> " + user.getId() + " or nickname <> '탈퇴한 사용자')");
        try {
            var proof = (WithdrawalVerification) session.getAttribute(WithdrawalVerification.ATTRIBUTE);
            assertThatThrownBy(() -> withdrawal.withdraw(user.getId(), proof, true)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(users.findById(user.getId()).orElseThrow().isActive()).isTrue();
            assertThat(credentials.findByUserId(user.getId())).isPresent();
            assertThat(accounts.findByUserId(user.getId())).hasSize(1);
            assertThat(memberships.findByCommunityIdAndUserId(f.communityId, user.getId())).isPresent();
            assertThat(members.findById(f.memberId).orElseThrow().isAnonymized()).isFalse();
            assertThat(session.isInvalid()).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from monitoring_events where event_code='USER_WITHDRAWN' and user_id=?", Long.class, user.getId())).isZero();
        } finally { jdbc.execute("alter table users drop constraint " + rule); }
    }

    @Test void staleProfilesCannotRestorePersonalInformationAfterWithdrawal() throws Exception {
        User user = emailUser(); var f = fixture(user, CommunityUserRole.MEMBER);
        var member = members.findById(f.memberId).orElseThrow();
        var account = memberAccounts.saveAndFlush(new CommunityMemberAccount(member, ExternalAccountProvider.DISCORD, "id-" + user.getId(), "private-discord"));
        var session = session(user); verifyPassword(session, PASSWORD); remove(session, true).andExpect(status().isNoContent());
        member.updateNickname("restored-name");
        assertThatThrownBy(() -> members.saveAndFlush(member)).isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
        account.updateExternalUsername("restored-discord");
        assertThatThrownBy(() -> memberAccounts.saveAndFlush(account)).isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
        assertThat(members.findById(f.memberId).orElseThrow().getNickname()).isEqualTo(User.WITHDRAWN_NAME);
        assertThat(memberAccounts.findById(account.getId()).orElseThrow().getExternalUsername()).isNull();
    }

    @Test void successImmediatelyInvalidatesAllRegisteredSessionsButFailureDoesNot() throws Exception {
        var user = emailUser(); var first = new org.springframework.mock.web.MockHttpServletRequest();
        var second = new org.springframework.mock.web.MockHttpServletRequest();
        authSessions.authenticate(first, user); authSessions.authenticate(second, user);
        var firstSession = (MockHttpSession) first.getSession(false);
        var secondSession = (MockHttpSession) second.getSession(false);
        remove(firstSession, true).andExpect(status().isForbidden());
        assertThat(secondSession.isInvalid()).isFalse();
        verifyPassword(firstSession, PASSWORD); remove(firstSession, true).andExpect(status().isNoContent());
        assertThat(firstSession.isInvalid()).isTrue();
        assertThat(secondSession.isInvalid()).isTrue();
    }

    User emailUser() { return emailAuth.register(new SignupRequest(email = UUID.randomUUID() + "@example.com", PASSWORD, PASSWORD, "private-user")); }
    User discordUser() { return discordAuth.findOrCreateUser(new DiscordApiUser("id-" + UUID.randomUUID(), "private-discord", "private-user", null)); }
    DiscordApiUser identity(User user) {
        var account = accounts.findByUserIdAndProvider(user.getId(), ExternalAccountProvider.DISCORD).orElseThrow();
        return new DiscordApiUser(account.getExternalUserId(), "private-discord", "private-user", null);
    }
    MockHttpSession session(User user) {
        var session = new MockHttpSession(); session.setAttribute(CurrentUserSession.USER_ID, user.getId()); token(session); return session;
    }
    String token(MockHttpSession session) { return SessionCsrfTokens.getOrCreate(session); }
    ResultActions verifyPassword(MockHttpSession session, String password) throws Exception {
        return mvc.perform(post("/api/account/withdrawal/verify").session(session).header(SessionCsrfTokens.HEADER, token(session))
                .contentType("application/json").content(json.writeValueAsString(Map.of("password", password))));
    }
    ResultActions remove(MockHttpSession session, boolean acknowledged) throws Exception {
        return mvc.perform(delete("/api/account").session(session).header(SessionCsrfTokens.HEADER, token(session))
                .contentType("application/json").content(json.writeValueAsString(Map.of("acknowledged", acknowledged))));
    }
    String startDiscord(MockHttpSession session) throws Exception {
        var response = mvc.perform(post("/api/auth/discord/withdrawal").session(session).header(SessionCsrfTokens.HEADER, token(session)))
                .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("authorizationUrl").asText();
    }
    JsonNode getJson(String path, MockHttpSession session) throws Exception {
        return json.readTree(mvc.perform(get(path).session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    Fixture fixture(User user, CommunityUserRole role) {
        return tx.execute(ignored -> {
            var community = communities.save(new Community("치즈-" + UUID.randomUUID()));
            var game = games.save(new CommunityGame(community, GameType.BATTLEGROUNDS_KAKAO));
            var owner = users.save(new User("owner"));
            memberships.save(new CommunityUser(community, owner, role == CommunityUserRole.OWNER ? CommunityUserRole.ADMIN : CommunityUserRole.OWNER));
            var member = members.save(new CommunityMember(community, user.getNickname()));
            var membership = new CommunityUser(community, user, role); membership.linkCommunityMember(member); memberships.save(membership);
            return new Fixture(community.getId(), game.getId(), membership.getId(), member.getId(), owner);
        });
    }
    Long[] history(User user, Fixture f) {
        return tx.execute(ignored -> {
            var community = em.find(Community.class, f.communityId); var game = em.find(CommunityGame.class, f.gameId);
            var member = em.find(CommunityMember.class, f.memberId); var membership = em.find(CommunityUser.class, f.membershipId);
            em.persist(new DiscordCommunityConnection(community, "guild-" + community.getId(), "서버"));
            accounts.save(new UserExternalAccount(user, ExternalAccountProvider.DISCORD, "id-" + user.getId(), "private-discord"));
            memberAccounts.save(new CommunityMemberAccount(member, ExternalAccountProvider.DISCORD, "id-" + user.getId(), "private-discord", "private-display", NOW));
            memberAccounts.save(new CommunityMemberAccount(member, PubgPlatform.KAKAO, "pubg-" + user.getId(), "private-pubg"));
            var bingo = new BingoEvent(community, game, membership, "과거 빙고", "", 3, 1, false, false, false, false,
                    NOW.minusSeconds(1000), NOW.minusSeconds(100), BingoStatus.COMPLETED, NOW.minusSeconds(2000));
            var cell = new BingoCell(bingo, 0, BingoMissionType.KILLS, BingoAggregationType.EVENT_TOTAL, BingoOperator.GREATER_THAN_OR_EQUAL,
                    BigDecimal.TEN, null, Map.of(), null); bingo.addCell(cell); em.persist(bingo);
            var participant = new BingoParticipant(bingo, membership, member, "pubg-" + user.getId(), "private-pubg", NOW.minusSeconds(1000), NOW.minusSeconds(1000));
            participant.updateLines(1, true, false, NOW.minusSeconds(200)); em.persist(participant);
            var progress = new BingoProgress(participant, cell, NOW); progress.apply(BigDecimal.TEN, 1, true, "match-1", NOW.minusSeconds(200), NOW); em.persist(progress);
            em.persist(new BingoLineCompletion(participant, "ROW_0", NOW.minusSeconds(200)));
            var competition = new KillCompetition(community, game, member, "과거 킬내기", KillCompetitionGameMode.SQUAD, NOW.minusSeconds(100), NOW.minusSeconds(2000));
            em.persist(competition); var team = new KillCompetitionTeam(competition, "팀", 0); competition.replaceTeams(List.of(team)); em.persist(team);
            var kp = new KillCompetitionParticipant(competition, member, "pubg-" + user.getId(), "private-pubg"); kp.assignTeam(team); kp.recordFinal(15, 1, 15);
            competition.addParticipant(kp); em.persist(kp); competition.complete(NOW.minusSeconds(100));
            em.persist(new KillCompetitionMatchResult(competition, kp, "match-1", NOW.minusSeconds(200), 15, 1, 15, 0, 15));
            var attendance = new CommunityAttendance(member, LocalDate.of(2026, 10, 6), NOW.minusSeconds(500)); em.persist(attendance);
            var score = new CommunityMemberScore(member, NOW); score.add(15, NOW); em.persist(score);
            em.persist(new CommunityScoreHistory(member, 15, CommunityScoreType.ATTENDANCE, "출석", CommunityScoreReferenceType.ATTENDANCE, attendance.getId(), NOW));
            var post = new CommunityPost(community, membership, CommunityPostCategory.FREE, "글", "내용", false, false, NOW); em.persist(post);
            em.persist(new CommunityPostComment(post, membership, "댓글", NOW));
            var voice = new DiscordVoiceSession(community, "guild", "id-" + user.getId(), "channel", "채널", NOW.minusSeconds(600)); voice.close(NOW.minusSeconds(60)); em.persist(voice);
            em.flush(); return new Long[]{bingo.getId(), competition.getId(), post.getId(), participant.getId(), voice.getId()};
        });
    }
    record Fixture(Long communityId, Long gameId, Long membershipId, Long memberId, User owner) {}

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        Clock withdrawalClock() { return Clock.fixed(NOW, ZoneId.of("Asia/Seoul")); }
    }
}
