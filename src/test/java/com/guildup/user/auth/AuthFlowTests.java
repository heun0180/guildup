package com.guildup.user.auth;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.community.service.*;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.discord.oauth.client.DiscordApiClient;
import com.guildup.discord.oauth.client.dto.*;
import com.guildup.user.auth.dto.SignupRequest;
import com.guildup.user.auth.exception.AuthException;
import com.guildup.user.auth.service.*;
import com.guildup.user.domain.User;
import com.guildup.user.domain.UserExternalAccount;
import com.guildup.user.repository.*;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 실제 Entity/트랜잭션/세션/CSRF/커뮤니티 경계를 함께 검증한다. 운영 DB는 사용하지 않는다. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-flow;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "discord.oauth.client-id=test-client", "discord.oauth.client-secret=test-secret",
        "discord.oauth.redirect-uri=http://localhost/api/discord/oauth/callback"
})
@AutoConfigureMockMvc
class AuthFlowTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired UserCredentialRepository credentials;
    @Autowired UserExternalAccountRepository externalAccounts;
    @Autowired CredentialAuthService emailAuth;
    @Autowired DiscordLoginService discordAuth;
    @Autowired PasswordEncoder encoder;
    @Autowired CommunityService communities;
    @Autowired CommunityUserRepository memberships;
    @Autowired CommunityMemberRepository members;
    @Autowired CommunityMemberAccountRepository memberAccounts;
    @Autowired DiscordCommunityConnectionRepository connections;
    @Autowired CommunityMemberDiscordStateService discordState;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DiscordApiClient discord;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    final ObjectMapper json = new ObjectMapper();
    static final String PASSWORD = "GuildUp123!";
    String suffix;

    @BeforeEach void setUp() { suffix = UUID.randomUUID().toString(); }

    @Test void signupCreatesOneUserWithNormalizedEmailHashAndSharedRotatedSession() throws Exception {
        long count = users.count();
        MockHttpSession session = anonymous();
        String oldId = session.getId();
        String oldToken = token(session);
        session.setAttribute("PRE_LOGIN_CONTEXT", "keep");
        var response = signup(session, "  User-" + suffix + "@EXAMPLE.COM  ", PASSWORD, PASSWORD, "  새 사용자  ")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.nickname").value("새 사용자"))
                .andReturn().getResponse();
        Long userId = json.readTree(response.getContentAsString()).get("id").asLong();
        assertThat(session.getAttribute(CurrentUserSession.USER_ID)).isEqualTo(userId);
        assertThat(session.getId()).isNotEqualTo(oldId);
        assertThat(token(session)).isNotEqualTo(oldToken);
        assertThat(session.getAttribute("PRE_LOGIN_CONTEXT")).isEqualTo("keep");
        assertThat(users.count()).isEqualTo(count + 1);
        var credential = credentials.findByUserId(userId).orElseThrow();
        assertThat(credential.getEmail()).isEqualTo("user-" + suffix + "@example.com");
        assertThat(credential.getPasswordHash()).startsWith("{bcrypt}").isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, credential.getPasswordHash())).isTrue();
        assertThat(credential.isEmailVerified()).isFalse();
        assertThat(externalAccounts.findByUserId(userId)).isEmpty();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(userId));
        mvc.perform(get("/api/auth/account").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(false)).andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-Token", oldToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test void duplicateEmailIsRejectedAfterCaseAndWhitespaceNormalizationWithoutOrphanUser() throws Exception {
        var user = emailUser();
        long count = users.count();
        signup(anonymous(), "  " + email().toUpperCase(Locale.ROOT) + "  ", PASSWORD, PASSWORD, "중복")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("이미 사용 중인 이메일입니다."));
        assertThat(users.count()).isEqualTo(count);
        assertThat(credentials.findByEmail(email()).orElseThrow().getUser().getId()).isEqualTo(user.getId());
    }

    @ParameterizedTest
    @CsvSource({"wrong-email,GuildUp123!,GuildUp123!,INVALID_EMAIL", "valid@example.com,short,short,INVALID_PASSWORD",
            "valid@example.com,abcdefgh,abcdefgh,INVALID_PASSWORD", "valid@example.com,GuildUp123!,Different123!,PASSWORD_MISMATCH"})
    void invalidSignupHasUsefulErrorsAndDoesNotCreateUser(String email, String password, String confirm, String code) throws Exception {
        long count = users.count();
        signup(anonymous(), email, password, confirm, "닉네임").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code));
        assertThat(users.count()).isEqualTo(count);
    }

    @Test void utf8PasswordOverBcryptLimitIsRejectedWithoutTruncation() throws Exception {
        String password = "가".repeat(25) + "A1";
        signup(anonymous(), email(), password, password, "닉네임").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
    }

    @Test void wrongPasswordAndUnknownEmailDoNotAuthenticateOrExposeWhichAccountExists() throws Exception {
        emailUser();
        MockHttpSession session = anonymous();
        String id = session.getId(), csrf = token(session);
        var wrong = login(session, email(), "WrongPass123!").andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        var missing = login(session, "unknown-" + email(), "WrongPass123!").andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(wrong).isEqualTo(missing);
        assertThat(session.getAttribute(CurrentUserSession.USER_ID)).isNull();
        assertThat(session.getId()).isEqualTo(id);
        assertThat(token(session)).isEqualTo(csrf);
    }

    @Test void emailLoginUsesExistingUserAndSameSessionRotationAsDiscord() throws Exception {
        User user = emailUser();
        long count = users.count();
        MockHttpSession session = anonymous();
        String id = session.getId(), csrf = token(session);
        login(session, "  " + email().toUpperCase(Locale.ROOT) + " ", PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()));
        assertThat(session.getId()).isNotEqualTo(id);
        assertThat(token(session)).isNotEqualTo(csrf);
        assertThat(session.getAttribute(CurrentUserSession.USER_ID)).isEqualTo(user.getId());
        assertThat(users.count()).isEqualTo(count);
    }

    @Test void existingDiscordUserAndRepeatedLoginKeepIdMembershipsAndClanRecords() throws Exception {
        var discordUser = discordUser("legacy");
        User user = users.save(new User("기존 사용자"));
        externalAccounts.saveAndFlush(new UserExternalAccount(user, ExternalAccountProvider.DISCORD, discordUser.id(), discordUser.username()));
        var community = communities.createCommunity("기존 커뮤니티", user.getId());
        var clanMember = members.save(new CommunityMember(community, "기존 클랜원"));
        memberAccounts.saveAndFlush(new CommunityMemberAccount(clanMember, ExternalAccountProvider.DISCORD, discordUser.id(), "legacy"));
        long count = users.count();
        for (int i = 0; i < 2; i++) {
            MockHttpSession session = anonymous();
            String id = session.getId(), csrf = token(session);
            completeLogin(session, discordUser);
            assertThat(session.getAttribute(CurrentUserSession.USER_ID)).isEqualTo(user.getId());
            assertThat(session.getId()).isNotEqualTo(id);
            assertThat(token(session)).isNotEqualTo(csrf);
            mvc.perform(get("/api/communities/" + community.getId()).session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("OWNER"));
        }
        assertThat(users.count()).isEqualTo(count);
        assertThat(memberAccounts.findByCommunityIdAndProviderAndExternalUserId(community.getId(), ExternalAccountProvider.DISCORD, discordUser.id())
                .orElseThrow().getCommunityMember().getId()).isEqualTo(clanMember.getId());
    }

    @Test void discordUserAddsEmailToSameUserWithoutChangingCommunityMemberOrPermissions() throws Exception {
        var discordUser = discordUser("add-email");
        User user = discordAuth.findOrCreateUser(discordUser);
        var community = communities.createCommunity("기록 유지", user.getId());
        var member = members.save(new CommunityMember(community, "기존 클랜원"));
        var membership = memberships.findByCommunityIdAndUserId(community.getId(), user.getId()).orElseThrow();
        membership.linkCommunityMember(member);
        memberships.saveAndFlush(membership);
        MockHttpSession session = signedIn(user);
        mutate("/api/communities/" + community.getId() + "/attendance", session, Map.of()).andExpect(status().isOk());
        long count = users.count();
        mutate("/api/auth/credentials", session, Map.of("email", email(), "password", PASSWORD, "passwordConfirmation", PASSWORD))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(user.getId()));
        assertThat(users.count()).isEqualTo(count);
        assertThat(session.getAttribute(CurrentUserSession.USER_ID)).isEqualTo(user.getId());
        assertThat(memberId(community, user)).isEqualTo(member.getId());
        assertThat(jdbc.queryForObject("select count(*) from community_attendances where community_member_id = ?", Long.class, member.getId())).isEqualTo(1);
        mvc.perform(get("/api/communities/" + community.getId()).session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OWNER"));
        login(anonymous(), email(), PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.getId()));
        mutate("/api/auth/credentials", session, Map.of("email", "second-" + email(), "password", PASSWORD, "passwordConfirmation", PASSWORD))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CREDENTIAL_ALREADY_EXISTS"));
    }

    @Test void authenticatedSignupIsRejectedAndCannotAccidentallyCreateAnotherUser() throws Exception {
        User user = emailUser();
        long count = users.count();
        signup(signedIn(user), "another-" + email(), PASSWORD, PASSWORD, "다른 계정").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_LOGGED_IN"));
        assertThat(users.count()).isEqualTo(count);
    }

    @Test void emailUserLinksDiscordWithoutCreatingOrSwitchingUserAndCanLoginBothWays() throws Exception {
        User user = emailUser();
        MockHttpSession session = signedIn(user);
        var discordUser = discordUser("link");
        long count = users.count();
        completeLink(session, discordUser, "/account.html?discordLinked=true");
        assertThat(users.count()).isEqualTo(count);
        assertThat(session.getAttribute(CurrentUserSession.USER_ID)).isEqualTo(user.getId());
        assertThat(externalAccounts.findByProviderAndExternalUserId(ExternalAccountProvider.DISCORD, discordUser.id()).orElseThrow().getUser().getId()).isEqualTo(user.getId());
        MockHttpSession discordSession = anonymous();
        completeLogin(discordSession, discordUser);
        assertThat(discordSession.getAttribute(CurrentUserSession.USER_ID)).isEqualTo(user.getId());
        login(anonymous(), email(), PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(user.getId()));
        completeLink(session, discordUser, "/account.html?discordLinked=true");
        assertThat(externalAccounts.findByUserId(user.getId())).hasSize(1);
    }

    @Test void discordAlreadyOwnedByAnotherUserIsRejectedWithoutMerging() throws Exception {
        var discordUser = discordUser("conflict");
        var owner = discordAuth.findOrCreateUser(discordUser);
        var user = emailUser();
        var originalCommunity = communities.createCommunity("원래 커뮤니티", owner.getId());
        var emailCommunity = communities.createCommunity("이메일 커뮤니티", user.getId());
        MockHttpSession session = signedIn(user);
        long count = users.count();
        completeLink(session, discordUser, "/account.html?oauthError=DISCORD_ACCOUNT_CONFLICT");
        assertThat(session.getAttribute(CurrentUserSession.USER_ID)).isEqualTo(user.getId());
        assertThat(users.count()).isEqualTo(count);
        assertThat(externalAccounts.findByUserId(user.getId())).isEmpty();
        assertThat(memberships.findByCommunityIdAndUserId(originalCommunity.getId(), owner.getId())).isPresent();
        assertThat(memberships.findByCommunityIdAndUserId(emailCommunity.getId(), user.getId())).isPresent();
        assertThat(memberships.findByCommunityIdAndUserId(originalCommunity.getId(), user.getId())).isEmpty();
    }

    @Test void linkStateIsSingleUseExpiresAndIsBoundToInitiatingUser() throws Exception {
        User user = emailUser();
        MockHttpSession session = signedIn(user);
        String state = startLink(session);
        mvc.perform(get("/api/auth/discord/callback").session(session).param("code", "unused").param("state", "wrong"))
                .andExpect(redirectedUrl("/account.html?oauthError=session"));
        mvc.perform(get("/api/auth/discord/callback").session(session).param("code", "unused").param("state", state))
                .andExpect(redirectedUrl("/login.html?oauthError=session"));
        startLink(session);
        var attempt = (DiscordAuthAttempt) session.getAttribute(DiscordAuthAttempt.ATTRIBUTE);
        session.setAttribute(DiscordAuthAttempt.ATTRIBUTE, new DiscordAuthAttempt(attempt.state(), attempt.redirectUri(), attempt.purpose(), attempt.userId(), Instant.now().minusSeconds(601)));
        mvc.perform(get("/api/auth/discord/callback").session(session).param("code", "unused").param("state", attempt.state()))
                .andExpect(redirectedUrl("/account.html?oauthError=session"));
        state = startLink(session);
        session.setAttribute(CurrentUserSession.USER_ID, users.save(new User("다른 사용자")).getId());
        mvc.perform(get("/api/auth/discord/callback").session(session).param("code", "unused").param("state", state))
                .andExpect(redirectedUrl("/account.html?oauthError=session"));
        verifyNoInteractions(discord);
    }

    @Test void anonymousSignupAndLoginRequireSessionCsrfAndCannotUseOtherSessionToken() throws Exception {
        MockHttpSession session = anonymous();
        for (String endpoint : List.of("/api/auth/signup", "/api/auth/login")) {
            mvc.perform(post(endpoint).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_MISSING"));
            mvc.perform(post(endpoint).session(session).header("X-CSRF-Token", token(anonymous())).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
            mvc.perform(post(endpoint + ";param=ignored").contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_MISSING"));
        }
        mvc.perform(post("/api/auth/discord/link")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/credentials").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void authenticatedMutationsRemainProtectedAndLogoutRevokesAccess() throws Exception {
        var user = emailUser();
        MockHttpSession session = signedIn(user);
        for (String endpoint : List.of("/api/auth/credentials", "/api/auth/discord/link", "/api/auth/logout")) {
            mvc.perform(post(endpoint).session(session).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_MISSING"));
        }
        mutate("/api/auth/logout", session, Map.of()).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/account")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me/communities")).andExpect(status().isUnauthorized());
    }

    @Test void discordAbsentUserCanCreateJoinAttendAndReadOrdinaryScreensAndPermissionChecksRemain() throws Exception {
        var owner = emailUser();
        MockHttpSession ownerSession = signedIn(owner);
        var created = mutate("/api/communities", ownerSession, Map.of("name", "이메일 커뮤니티", "gameType", "BATTLEGROUNDS_KAKAO"), suffix)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long communityId = json.readTree(created).get("id").asLong();
        mvc.perform(get("/api/community-discoveries/discord").session(ownerSession)).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/auth/me/communities").session(ownerSession)).andExpect(status().isOk());
        mvc.perform(get("/api/communities/" + communityId).session(ownerSession)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OWNER"));
        mvc.perform(get("/api/communities/" + communityId + "/members").session(ownerSession)).andExpect(status().isOk());
        mutate("/api/communities/" + communityId + "/attendance", ownerSession, Map.of()).andExpect(status().isOk());
        connections.saveAndFlush(new DiscordCommunityConnection(communityRepository.findById(communityId).orElseThrow(), "guild-" + suffix, "서버"));
        Long gameId = jdbc.queryForObject("select id from community_games where community_id = ?", Long.class, communityId);
        mvc.perform(get("/api/communities/" + communityId + "/games/" + gameId + "/nickname-rule").session(ownerSession))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DISCORD_NOT_LINKED"));
        User member = users.save(new User("초대 받은 이메일 사용자"));
        String invitation = communities.getInvitation(owner.getId(), communityId);
        communities.joinInvitation(member.getId(), invitation);
        MockHttpSession memberSession = signedIn(member);
        mutate("/api/communities/" + communityId + "/attendance", memberSession, Map.of()).andExpect(status().isOk());
        mvc.perform(put("/api/communities/" + communityId + "/ranking-settings").session(memberSession)
                        .header("X-CSRF-Token", token(memberSession)).contentType("application/json").content("{\"periodType\":\"MONTHLY\"}"))
                .andExpect(status().isForbidden());
        var membership = memberships.findByCommunityIdAndUserId(communityId, member.getId()).orElseThrow();
        membership.changeRole(CommunityUserRole.ADMIN); memberships.saveAndFlush(membership);
        mvc.perform(put("/api/communities/" + communityId + "/ranking-settings").session(memberSession)
                        .header("X-CSRF-Token", token(memberSession)).contentType("application/json").content("{\"periodType\":\"MONTHLY\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/communities/" + communityId).session(memberSession).header("X-CSRF-Token", token(memberSession)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(jda);
    }

    @Autowired CommunityRepository communityRepository;

    @Test void lateDiscordSyncReusesNativeMemberAndPreservesAttendanceAndPubgAccount() throws Exception {
        var user = emailUser();
        var session = signedIn(user);
        var created = mutate("/api/communities", session, Map.of("name", "기록 보존", "gameType", "BATTLEGROUNDS_KAKAO"), suffix)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var community = communityRepository.findById(json.readTree(created).get("id").asLong()).orElseThrow();
        Long memberId = memberId(community, user);
        var member = members.findById(memberId).orElseThrow();
        var pubg = memberAccounts.saveAndFlush(new CommunityMemberAccount(member, com.guildup.pubg.model.PubgPlatform.KAKAO, "pubg-" + suffix, "player"));
        mutate("/api/communities/" + community.getId() + "/attendance", session, Map.of()).andExpect(status().isOk());
        var discordUser = discordUser("late");
        completeLink(session, discordUser, "/account.html?discordLinked=true");
        connections.saveAndFlush(new DiscordCommunityConnection(community, "guild-" + suffix, "서버"));
        long memberCount = members.count();
        var outcome = discordState.synchronizeMember(community.getId(), discordMember(discordUser), Set.of("role"), Instant.now());
        assertThat(outcome).isEqualTo(CommunityMemberDiscordStateService.MemberSyncOutcome.UPDATED);
        assertThat(members.count()).isEqualTo(memberCount);
        assertThat(memberId(community, user)).isEqualTo(memberId);
        assertThat(memberAccounts.findById(pubg.getId())).isPresent();
        assertThat(memberAccounts.findByCommunityIdAndProviderAndExternalUserId(community.getId(), ExternalAccountProvider.DISCORD, discordUser.id())
                .orElseThrow().getCommunityMember().getId()).isEqualTo(memberId);
        assertThat(jdbc.queryForObject("select count(*) from community_attendances where community_member_id = ?", Long.class, memberId)).isEqualTo(1);
    }

    @Test void separateExistingDiscordClanMemberIsReportedWithoutReplacingNativeLinkOrMerging() throws Exception {
        var user = emailUser();
        var community = communities.createCommunity("충돌 기록", user.getId());
        var nativeMember = members.save(new CommunityMember(community, "이메일 클랜원"));
        var existingMember = members.save(new CommunityMember(community, "Discord 클랜원"));
        var membership = memberships.findByCommunityIdAndUserId(community.getId(), user.getId()).orElseThrow();
        membership.linkCommunityMember(nativeMember); memberships.saveAndFlush(membership);
        var discordUser = discordUser("member-conflict");
        memberAccounts.saveAndFlush(new CommunityMemberAccount(existingMember, ExternalAccountProvider.DISCORD, discordUser.id(), discordUser.username()));
        MockHttpSession session = signedIn(user);
        completeLink(session, discordUser, "/account.html?discordLinked=true");
        mvc.perform(get("/api/auth/account").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberLinkConflicts[0].communityId").value(community.getId()));
        assertThat(memberId(community, user)).isEqualTo(nativeMember.getId());
        assertThat(members.findById(existingMember.getId())).isPresent();
    }

    @Test void simultaneousEmailSignupsHaveOneWinnerAndNoOrphanUsers() throws Exception {
        long count = users.count();
        List<Object> results = concurrent(4, () -> {
            try { return emailUser(); } catch (AuthException exception) { return exception; }
        });
        assertThat(results.stream().filter(User.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(AuthException.class::isInstance).map(AuthException.class::cast))
                .allSatisfy(exception -> assertThat(exception.getCode()).isEqualTo("EMAIL_ALREADY_USED"));
        assertThat(users.count()).isEqualTo(count + 1);
    }

    @Test void simultaneousFirstDiscordLoginsResolveSameUserAndLeaveNoOrphanUsers() throws Exception {
        long count = users.count();
        var discordUser = discordUser("race");
        var results = concurrent(6, () -> discordAuth.findOrCreateUser(discordUser));
        assertThat(results.stream().map(User::getId).distinct()).hasSize(1);
        assertThat(users.count()).isEqualTo(count + 1);
    }

    private String email() { return "user-" + suffix + "@example.com"; }
    private User emailUser() { return emailAuth.register(new SignupRequest(email(), PASSWORD, PASSWORD, "이메일 사용자")); }
    private MockHttpSession anonymous() throws Exception {
        MockHttpSession session = new MockHttpSession(); token(session); return session;
    }
    private MockHttpSession signedIn(User user) { var session = new MockHttpSession(); session.setAttribute(CurrentUserSession.USER_ID, user.getId()); return session; }
    private String token(MockHttpSession session) throws Exception {
        var result = mvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
    private org.springframework.test.web.servlet.ResultActions signup(MockHttpSession session, String email, String password, String confirm, String nickname) throws Exception {
        return mutate("/api/auth/signup", session, Map.of("email", email, "password", password, "passwordConfirmation", confirm, "nickname", nickname));
    }
    private org.springframework.test.web.servlet.ResultActions login(MockHttpSession session, String email, String password) throws Exception {
        return mutate("/api/auth/login", session, Map.of("email", email, "password", password));
    }
    private org.springframework.test.web.servlet.ResultActions mutate(String path, MockHttpSession session, Map<String, ?> body) throws Exception {
        return mutate(path, session, body, null);
    }
    private org.springframework.test.web.servlet.ResultActions mutate(String path, MockHttpSession session, Map<String, ?> body, String key) throws Exception {
        var request = post(path).session(session).header("X-CSRF-Token", token(session)).contentType("application/json").content(json.writeValueAsString(body));
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }
    private DiscordApiUser discordUser(String name) { return new DiscordApiUser(name + "-" + suffix, name, "Discord 사용자", null); }
    private void stubDiscord(DiscordApiUser user) {
        when(discord.exchangeCode("code-" + suffix, "http://localhost/api/auth/discord/callback"))
                .thenReturn(new DiscordAccessTokenResponse("token-" + suffix, "Bearer", 3600, "identify"));
        when(discord.getCurrentUser("token-" + suffix)).thenReturn(user);
    }
    private void completeLogin(MockHttpSession session, DiscordApiUser user) throws Exception {
        stubDiscord(user);
        String location = mvc.perform(get("/api/auth/discord/authorize").session(session)).andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        String state = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state");
        mvc.perform(get("/api/auth/discord/callback").session(session).param("state", state).param("code", "code-" + suffix))
                .andExpect(redirectedUrl("/communities.html"));
    }
    private String startLink(MockHttpSession session) throws Exception {
        var response = mutate("/api/auth/discord/link", session, Map.of()).andExpect(status().isOk()).andReturn().getResponse();
        return UriComponentsBuilder.fromUriString(json.readTree(response.getContentAsString()).get("authorizationUrl").asText())
                .build().getQueryParams().getFirst("state");
    }
    private void completeLink(MockHttpSession session, DiscordApiUser user, String expectedRedirect) throws Exception {
        stubDiscord(user);
        String state = startLink(session);
        mvc.perform(get("/api/auth/discord/callback").session(session).param("state", state).param("code", "code-" + suffix))
                .andExpect(redirectedUrl(expectedRedirect));
    }
    private Long memberId(Community community, User user) { return jdbc.queryForObject("select community_member_id from community_users where community_id = ? and user_id = ?", Long.class, community.getId(), user.getId()); }
    private Member discordMember(DiscordApiUser user) {
        Member member = mock(Member.class); var discordUser = mock(net.dv8tion.jda.api.entities.User.class); Role role = mock(Role.class);
        when(discordUser.getId()).thenReturn(user.id()); when(discordUser.getName()).thenReturn(user.username()); when(discordUser.isBot()).thenReturn(false);
        when(member.getUser()).thenReturn(discordUser); when(member.getEffectiveName()).thenReturn(user.username());
        when(role.getId()).thenReturn("role"); when(member.getRoles()).thenReturn(List.of(role)); return member;
    }
    private <T> List<T> concurrent(int count, Callable<T> work) throws Exception {
        try (var executor = Executors.newFixedThreadPool(count)) {
            var start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) futures.add(executor.submit(() -> { start.await(); return work.call(); }));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }
}
