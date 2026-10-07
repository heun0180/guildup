package com.guildup.user.auth;

import com.guildup.account.domain.ExternalAccountProvider;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.discord.oauth.client.DiscordApiClient;
import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import com.guildup.pubg.model.PubgPlatform;
import com.guildup.user.auth.dto.*;
import com.guildup.user.auth.service.*;
import com.guildup.user.domain.User;
import com.guildup.user.repository.*;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:account-profile;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "discord.oauth.client-id=test-client", "discord.oauth.client-secret=test-secret",
        "discord.oauth.redirect-uri=http://localhost/api/discord/oauth/callback"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(AccountProfileTests.ClockConfig.class)
class AccountProfileTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired UserCredentialRepository credentials;
    @Autowired UserExternalAccountRepository accounts;
    @Autowired CredentialAuthService emailAuth;
    @Autowired DiscordLoginService discordAuth;
    @Autowired CommunityRepository communities;
    @Autowired CommunityUserRepository memberships;
    @Autowired CommunityMemberRepository members;
    @Autowired CommunityMemberAccountRepository memberAccounts;
    @Autowired DiscordCommunityConnectionRepository connections;
    @Autowired PlatformTransactionManager txManager;
    @MockitoBean DiscordApiClient discord;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    final ObjectMapper json = new ObjectMapper();
    static final String PASSWORD = "GuildUp123!";

    @Test void signedInUserCanReadOwnAccountAndOptionalLegacyProfileWithoutCredentialSecrets() throws Exception {
        var user = discordUser(); var session = session(user);
        mvc.perform(get("/api/account/profile").session(session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.nickname").value("기존 GuildUp 이름"))
                .andExpect(jsonPath("$.birthDate").isEmpty()).andExpect(jsonPath("$.avatarUrl").isEmpty())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(get("/api/auth/account").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.getId())).andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.canDisconnectDiscord").value(false));
        assertThat(users.findById(user.getId()).orElseThrow().getBirthDate()).isNull();
    }

    @Test void anonymousAccountReadsAndProfileWritesAndDisconnectAreRejected() throws Exception {
        mvc.perform(get("/api/account/profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/account")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/account/profile").contentType("application/json").content("{\"nickname\":\"Apple\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/account/connections/discord")).andExpect(status().isUnauthorized());
    }

    @Test void nicknameUsesSignupPolicyTrimsWhitespaceAllowsKoreanEnglishNumbersAndDuplicates() throws Exception {
        var user = discordUser(); var other = users.saveAndFlush(new User("애플 Apple_123!"));
        update(session(user), Map.of("nickname", "  애플 Apple_123!  ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("애플 Apple_123!"));
        assertThat(users.findById(user.getId()).orElseThrow().getNickname()).isEqualTo(other.getNickname());
        mvc.perform(get("/api/auth/me").session(session(user))).andExpect(jsonPath("$.nickname").value("애플 Apple_123!"));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"   ", "a\nb", "a\u0001b"})
    void invalidNicknameIsRejectedWithoutChangingProfile(String value) throws Exception {
        var user = discordUser(); var request = new HashMap<String, Object>(); request.put("nickname", value);
        update(session(user), request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_NICKNAME"));
        assertThat(users.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("기존 GuildUp 이름");
    }

    @Test void nicknameLengthBoundaryIsEnforced() throws Exception {
        var user = discordUser(); var session = session(user);
        update(session, Map.of("nickname", "가".repeat(50))).andExpect(status().isOk());
        update(session, Map.of("nickname", "가".repeat(51))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NICKNAME"));
        assertThat(users.findById(user.getId()).orElseThrow().getNickname()).hasSize(50);
    }

    @Test void validBirthDatePersistsAsDateAndOmittedPatchFieldsArePreserved() throws Exception {
        var user = discordUser(); var session = session(user);
        update(session, Map.of("birthDate", "1993-01-01")).andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("기존 GuildUp 이름")).andExpect(jsonPath("$.birthDate").value("1993-01-01"));
        update(session, Map.of("nickname", "내 닉네임")).andExpect(status().isOk()).andExpect(jsonPath("$.birthDate").value("1993-01-01"));
        assertThat(users.findById(user.getId()).orElseThrow().getBirthDate()).isEqualTo(LocalDate.of(1993, 1, 1));
        mvc.perform(get("/api/account/profile").session(session)).andExpect(jsonPath("$.birthDate").value("1993-01-01"));
    }

    @ParameterizedTest @NullAndEmptySource
    void birthDateCanBeClearedAndIsNeverRequired(String value) throws Exception {
        var user = discordUser(); var session = session(user);
        update(session, Map.of("birthDate", "1993-01-01")).andExpect(status().isOk());
        var body = new HashMap<String, Object>(); body.put("birthDate", value);
        update(session, body).andExpect(status().isOk()).andExpect(jsonPath("$.birthDate").isEmpty());
        assertThat(users.findById(user.getId()).orElseThrow().getBirthDate()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"2026-10-08", "2025-02-29", "1993-02-31", "1993-13-01", "0000-01-01", "1993-1-1", "not-a-date"})
    void futureAndNonexistentBirthDatesAreRejectedAndWholePatchRollsBack(String value) throws Exception {
        var user = discordUser();
        update(session(user), Map.of("nickname", "변경 실패", "birthDate", value)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BIRTH_DATE"));
        var stored = users.findById(user.getId()).orElseThrow();
        assertThat(stored.getBirthDate()).isNull(); assertThat(stored.getNickname()).isEqualTo("기존 GuildUp 이름");
    }

    @Test void leapDayAndTodayAreValid() throws Exception {
        var session = session(discordUser());
        update(session, Map.of("birthDate", "2000-02-29")).andExpect(status().isOk());
        update(session, Map.of("birthDate", "2026-10-07")).andExpect(status().isOk());
    }

    @Test void externalNameAndAvatarRefreshNeverOverwriteGuildUpNicknameOrBirthday() throws Exception {
        var identity = new DiscordApiUser("123" + Math.abs(UUID.randomUUID().getMostSignificantBits()), "apple_123", "Apple", "hash");
        var user = discordAuth.findOrCreateUser(identity); var session = session(user);
        update(session, Map.of("nickname", "나의 GuildUp 이름", "birthDate", "1993-01-01")).andExpect(status().isOk());
        assertThat(discordAuth.findOrCreateUser(new DiscordApiUser(identity.id(), "changed_discord", "Discord 새 이름", "a_newhash")).getId())
                .isEqualTo(user.getId());
        mvc.perform(get("/api/account/profile").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("나의 GuildUp 이름"))
                .andExpect(jsonPath("$.birthDate").value("1993-01-01"))
                .andExpect(jsonPath("$.avatarUrl").value("https://cdn.discordapp.com/avatars/" + identity.id() + "/a_newhash.gif"));
        mvc.perform(get("/api/auth/account").session(session)).andExpect(jsonPath("$.discordUsername").value("changed_discord"))
                .andExpect(jsonPath("$.discordDisplayName").value("Discord 새 이름"));
        discordAuth.linkAccount(user.getId(), new DiscordApiUser(identity.id(), "another", "다른 이름", null));
        assertThat(users.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("나의 GuildUp 이름");
        mvc.perform(get("/api/account/profile").session(session)).andExpect(jsonPath("$.avatarUrl").isEmpty());
    }

    @Test void discordNicknameChangesAlsoPreserveUneditedLegacyGuildUpNickname() {
        var user = discordUser(); var identity = accounts.findByUserIdAndProvider(user.getId(), ExternalAccountProvider.DISCORD).orElseThrow();
        discordAuth.findOrCreateUser(new DiscordApiUser(identity.getExternalUserId(), "changed", "새 외부 이름", null));
        assertThat(users.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("기존 GuildUp 이름");
    }

    @Test void clientCannotSelectAnotherUserByBodyQueryOrPath() throws Exception {
        var user = discordUser(); var other = discordUser(); var session = session(user);
        update(session, Map.of("userId", other.getId(), "nickname", "자신만 수정")).andExpect(status().isOk());
        mvc.perform(patch("/api/account/profile").session(session).param("userId", other.getId().toString())
                .header(SessionCsrfTokens.HEADER, SessionCsrfTokens.getOrCreate(session))
                .contentType("application/json").content("{\"nickname\":\"자신만 수정\"}")).andExpect(status().isOk());
        mvc.perform(patch("/api/account/profile/" + other.getId()).session(session(user)))
                .andExpect(status().isNotFound());
        assertThat(users.findById(other.getId()).orElseThrow().getNickname()).isEqualTo("기존 GuildUp 이름");
        assertThat(users.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("자신만 수정");
    }

    @Test void profileMutationAndDisconnectRequireSessionCsrfToken() throws Exception {
        var user = discordUser(); var session = session(user);
        for (String endpoint : List.of("/api/account/profile", "/api/account/connections/discord")) {
            var builder = endpoint.endsWith("profile") ? patch(endpoint).contentType("application/json").content("{\"nickname\":\"Apple\"}") : delete(endpoint);
            mvc.perform(builder.session(session)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_MISSING"));
        }
        mvc.perform(patch("/api/account/profile").session(session).header(SessionCsrfTokens.HEADER, "wrong")
                .contentType("application/json").content("{\"nickname\":\"Apple\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        assertThat(users.findById(user.getId()).orElseThrow().getNickname()).isEqualTo("기존 GuildUp 이름");
    }

    @Test void discordOnlyUserCannotRemoveLastLoginMethod() throws Exception {
        var user = discordUser();
        disconnect(session(user)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_LOGIN_METHOD"));
        assertThat(accounts.findByUserIdAndProvider(user.getId(), ExternalAccountProvider.DISCORD)).isPresent();
    }

    @Test void emailUserCanDisconnectWhileCommunityAndPubgIdentitiesArePreserved() throws Exception {
        var user = emailAuth.register(new SignupRequest(UUID.randomUUID() + "@example.com", PASSWORD, PASSWORD, "GuildUp Apple"));
        String discordId = "id-" + UUID.randomUUID();
        discordAuth.linkAccount(user.getId(), new DiscordApiUser(discordId, "apple_123", "Discord Apple", null));
        var fixture = new TransactionTemplate(txManager).execute(ignored -> {
            var community = communities.save(new Community("프로필 테스트 커뮤니티"));
            var connection = connections.save(new DiscordCommunityConnection(community, "guild-" + UUID.randomUUID(), "Discord 서버"));
            var member = members.save(new CommunityMember(community, "클랜원 Apple"));
            var membership = new CommunityUser(community, user, CommunityUserRole.OWNER); membership.linkCommunityMember(member); memberships.save(membership);
            var discordAccount = memberAccounts.save(new CommunityMemberAccount(member, ExternalAccountProvider.DISCORD, discordId, "apple_123"));
            var pubgAccount = memberAccounts.save(new CommunityMemberAccount(member, PubgPlatform.KAKAO, "pubg-id", "Cheeeze_Apple"));
            return List.of(connection.getId(), member.getId(), discordAccount.getId(), pubgAccount.getId());
        });
        var session = session(user);
        update(session, Map.of("nickname", "변경 GuildUp")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/account").session(session)).andExpect(jsonPath("$.canDisconnectDiscord").value(true));
        session.setAttribute(DiscordAuthAttempt.ATTRIBUTE, new DiscordAuthAttempt("state", "callback", DiscordAuthAttempt.Purpose.LINK_ACCOUNT, user.getId(), Instant.now()));
        disconnect(session).andExpect(status().isNoContent());
        disconnect(session).andExpect(status().isNoContent());
        assertThat(session.getAttribute(DiscordAuthAttempt.ATTRIBUTE)).isNull();
        assertThat(accounts.findByUserId(user.getId())).isEmpty();
        assertThat(connections.findById(fixture.get(0))).isPresent();
        assertThat(members.findById(fixture.get(1)).orElseThrow().getNickname()).isEqualTo("클랜원 Apple");
        assertThat(memberAccounts.findById(fixture.get(2)).orElseThrow().getExternalUsername()).isEqualTo("apple_123");
        assertThat(memberAccounts.findById(fixture.get(3)).orElseThrow().getExternalUsername()).isEqualTo("Cheeeze_Apple");
        var credential = credentials.findByUserId(user.getId()).orElseThrow();
        assertThat(emailAuth.login(new EmailLoginRequest(credential.getEmail(), PASSWORD)).getId()).isEqualTo(user.getId());
        mvc.perform(get("/api/account/profile").session(session)).andExpect(jsonPath("$.nickname").value("변경 GuildUp"))
                .andExpect(jsonPath("$.avatarUrl").isEmpty());
        mvc.perform(get("/api/auth/account").session(session)).andExpect(jsonPath("$.discordConnected").value(false));
    }

    @Test void withdrawnUserCannotRestoreProfileOrDisconnect() throws Exception {
        var user = discordUser(); var session = session(user);
        user.withdraw(Instant.now()); users.saveAndFlush(user);
        update(session, Map.of("nickname", "복원", "birthDate", "1993-01-01")).andExpect(status().isUnauthorized());
        assertThat(users.findById(user.getId()).orElseThrow().getNickname()).isEqualTo(User.WITHDRAWN_NAME);
        assertThat(users.findById(user.getId()).orElseThrow().getBirthDate()).isNull();
    }

    User discordUser() {
        return discordAuth.findOrCreateUser(new DiscordApiUser("id-" + UUID.randomUUID(), "apple_123", "기존 GuildUp 이름", null));
    }
    MockHttpSession session(User user) {
        var session = new MockHttpSession(); session.setAttribute(CurrentUserSession.USER_ID, user.getId()); return session;
    }
    ResultActions update(MockHttpSession session, Map<String, ?> body) throws Exception {
        return mvc.perform(patch("/api/account/profile").session(session).header(SessionCsrfTokens.HEADER, SessionCsrfTokens.getOrCreate(session))
                .contentType("application/json").content(json.writeValueAsString(body)));
    }
    ResultActions disconnect(MockHttpSession session) throws Exception {
        return mvc.perform(delete("/api/account/connections/discord").session(session)
                .header(SessionCsrfTokens.HEADER, SessionCsrfTokens.getOrCreate(session)));
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        Clock profileClock() { return Clock.fixed(Instant.parse("2026-10-07T09:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
}
