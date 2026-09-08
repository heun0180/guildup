package com.guildup.community;

import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.community.service.CommunityService;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.discord.oauth.client.DiscordApiClient;
import com.guildup.discord.oauth.store.DiscordBotInstallStore;
import com.guildup.discord.oauth.store.DiscordBotInstallSession;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
import com.guildup.user.repository.UserExternalAccountRepository;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:community-flow;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "discord.oauth.client-id=test-client", "discord.oauth.client-secret=test-secret",
        "discord.oauth.redirect-uri=http://localhost/api/discord/oauth/callback"
})
@AutoConfigureMockMvc
class CommunityFlowTests {
    @Autowired MockMvc mvc;
    @Autowired CommunityService service;
    @Autowired CommunityRepository communities;
    @Autowired CommunityUserRepository memberships;
    @Autowired DiscordCommunityConnectionRepository connections;
    @Autowired CommunityMemberRepository communityMembers;
    @Autowired CommunityMemberAccountRepository memberAccounts;
    @Autowired CommunityMemberRoleSettingRepository memberRoleSettings;
    @Autowired CommunityGameRepository communityGames;
    @Autowired CommunityGameActivityRuleRepository activityRules;
    @Autowired com.guildup.community.config.CommunityGameActivityRuleDataMigration activityRuleMigration;
    @Autowired UserRepository users;
    @Autowired UserExternalAccountRepository userExternalAccounts;
    @Autowired DiscordBotInstallStore installs;
    @Autowired com.guildup.discord.oauth.store.DiscordOAuthSessionStore oauthResults;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    @MockitoBean DiscordApiClient discord;
    User user;
    User other;
    MockHttpSession session;

    @BeforeEach
    void setUp() {
        activityRules.deleteAll();
        memberAccounts.deleteAll();
        communityMembers.deleteAll();
        memberRoleSettings.deleteAll();
        connections.deleteAll();
        memberships.deleteAll();
        communityGames.deleteAll();
        communities.deleteAll();
        users.deleteAll();
        user = users.save(new User("나"));
        other = users.save(new User("다른 사용자"));
        session = new MockHttpSession();
        session.setAttribute("LOGIN_USER_ID", user.getId());
    }

    @Test
    void listsOnlyMembershipsAndKeepsSingleCommunityAsArray() throws Exception {
        Community mine = service.createCommunity("치즈 클랜", user.getId());
        service.createCommunity("다른 커뮤니티", other.getId());
        for (String path : new String[]{"/api/auth/me/communities", "/api/communities"}) {
            mvc.perform(get(path).session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(mine.getId()))
                    .andExpect(jsonPath("$[0].name").value("치즈 클랜"))
                    .andExpect(jsonPath("$[0].role").value("OWNER"))
                    .andExpect(jsonPath("$[0].gameType").value("BATTLEGROUNDS_KAKAO"))
                    .andExpect(jsonPath("$[0].gameName").value("배틀그라운드 카카오"));
        }
    }

    @Test
    void returnsEmptyListAndRequiresLogin() throws Exception {
        mvc.perform(get("/api/auth/me/communities").session(session))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/auth/me/communities")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/communities")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/communities").contentType("application/json")
                .content("{\"name\":\"test\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void listsEveryAccessibleCommunityWithItsRole() throws Exception {
        service.createCommunity("내 커뮤니티", user.getId());
        Community shared = service.createCommunity("공유 커뮤니티", other.getId());
        memberships.save(new CommunityUser(shared, user, CommunityUserRole.ADMIN));
        mvc.perform(get("/api/auth/me/communities").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].role").value("ADMIN"));
    }

    @Test
    void createsCommunityAndLinksSessionUserAsOwner() throws Exception {
        mvc.perform(post("/api/communities").session(session).contentType("application/json")
                        .content("{\"name\":\"  치즈 클랜  \",\"gameType\":\"BATTLEGROUNDS_KAKAO\",\"userId\":" + other.getId() + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("치즈 클랜"))
                .andExpect(jsonPath("$.gameType").value("BATTLEGROUNDS_KAKAO"))
                .andExpect(jsonPath("$.gameName").value("배틀그라운드 카카오"));
        var list = memberships.findByUserIdOrderByCommunityIdAsc(user.getId());
        assertThat(list).hasSize(1);
        assertThat(list.getFirst().getRole()).isEqualTo(CommunityUserRole.OWNER);
        assertThat(memberships.findByUserIdOrderByCommunityIdAsc(other.getId())).isEmpty();
        assertThat(communityGames.findByCommunityIdOrderByIdAsc(list.getFirst().getCommunity().getId()))
                .extracting(CommunityGame::getGameType)
                .containsExactly(GameType.BATTLEGROUNDS_KAKAO);
    }

    @Test
    void createsSteamCommunityWithSelectedGame() throws Exception {
        mvc.perform(post("/api/communities").session(session).contentType("application/json")
                        .content("{\"name\":\"스팀 클랜\",\"gameType\":\"BATTLEGROUNDS_STEAM\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gameType").value("BATTLEGROUNDS_STEAM"))
                .andExpect(jsonPath("$.gameName").value("배틀그라운드 스팀"));

        assertThat(communityGames.findAll()).extracting(CommunityGame::getGameType)
                .containsExactly(GameType.BATTLEGROUNDS_STEAM);
    }

    @Test
    void createsReadsAndUpdatesOneActivityRulePerCommunityGame() throws Exception {
        Community mine = service.createCommunity("치즈 클랜", user.getId());
        String path = "/api/communities/" + mine.getId() + "/activity-rule";

        mvc.perform(get(path).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameType").value("BATTLEGROUNDS_KAKAO"))
                .andExpect(jsonPath("$.gameName").value("배틀그라운드 카카오"))
                .andExpect(jsonPath("$.activityPeriodDays").value(14))
                .andExpect(jsonPath("$.minimumClanMembersInRoster").value(2));
        mvc.perform(put(path).session(session).contentType("application/json")
                        .content("{\"activityPeriodDays\":14,\"minimumClanMembersInRoster\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minimumClanMembersInRoster").value(3));
        mvc.perform(put(path).session(session).contentType("application/json")
                        .content("{\"activityPeriodDays\":30,\"minimumClanMembersInRoster\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityPeriodDays").value(30));

        assertThat(activityRules.count()).isEqualTo(1);
    }

    @Test
    void backfillsExistingPubgCommunityByRelationshipWithoutChangingDiscordData() throws Exception {
        Community existing = communities.save(new Community("치즈 클랜"));
        CommunityGame game = communityGames.save(
                new CommunityGame(existing, GameType.BATTLEGROUNDS_KAKAO)
        );
        connections.save(new DiscordCommunityConnection(existing, "123456", "Cheeeze"));

        activityRuleMigration.run(null);
        activityRuleMigration.run(null);

        CommunityGameActivityRule rule = activityRules.findByCommunityGameId(game.getId()).orElseThrow();
        assertThat(rule.getActivityPeriodDays()).isEqualTo(14);
        assertThat(rule.getMinimumClanMembersInRoster()).isEqualTo(2);
        assertThat(activityRules.count()).isEqualTo(1);
        assertThat(connections.findByCommunityId(existing.getId()).orElseThrow().getDiscordGuildName())
                .isEqualTo("Cheeeze");
    }

    @Test
    void rejectsInvalidActivityRulesAndMemberUpdates() throws Exception {
        Community community = service.createCommunity("공유", other.getId());
        memberships.save(new CommunityUser(community, user, CommunityUserRole.MEMBER));
        String path = "/api/communities/" + community.getId() + "/activity-rule";

        mvc.perform(get(path).session(session)).andExpect(status().isOk());
        for (String body : new String[]{
                "{\"minimumClanMembersInRoster\":2}",
                "{\"activityPeriodDays\":0,\"minimumClanMembersInRoster\":2}",
                "{\"activityPeriodDays\":366,\"minimumClanMembersInRoster\":2}",
                "{\"activityPeriodDays\":14,\"minimumClanMembersInRoster\":1}",
                "{\"activityPeriodDays\":14,\"minimumClanMembersInRoster\":5}"
        }) {
            mvc.perform(put(path).session(session).contentType("application/json").content(body))
                    .andExpect(status().isForbidden());
        }

        Community owned = service.createCommunity("관리", user.getId());
        String ownedPath = "/api/communities/" + owned.getId() + "/activity-rule";
        mvc.perform(put(ownedPath).session(session).contentType("application/json")
                        .content("{\"minimumClanMembersInRoster\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put(ownedPath).session(session).contentType("application/json")
                        .content("{\"activityPeriodDays\":14,\"minimumClanMembersInRoster\":5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingAndUnsupportedGameTypes() throws Exception {
        mvc.perform(post("/api/communities").session(session).contentType("application/json")
                        .content("{\"name\":\"게임 없음\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/communities").session(session).contentType("application/json")
                        .content("{\"name\":\"잘못된 게임\",\"gameType\":\"PUBG\"}"))
                .andExpect(status().isBadRequest());

        assertThat(communities.count()).isZero();
        assertThat(communityGames.count()).isZero();
    }

    @Test
    void rollsBackCommunityInsertWhenOwnerInsertFails() {
        // 실제 DB 제약 위반을 발생시켜 서비스 프록시의 트랜잭션 롤백을 검증한다.
        jdbc.execute("ALTER TABLE community_users ADD CONSTRAINT reject_owner CHECK (role <> 'OWNER')");
        try {
            assertThatThrownBy(() -> service.createCommunity("롤백 대상", user.getId()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(communities.count()).isZero();
            assertThat(memberships.count()).isZero();
            assertThat(communityGames.count()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE community_users DROP CONSTRAINT reject_owner");
        }
    }

    @Test
    void rollsBackCommunityAndOwnerWhenCommunityGameInsertFails() {
        jdbc.execute("ALTER TABLE community_games ADD CONSTRAINT reject_steam CHECK (game_type <> 'BATTLEGROUNDS_STEAM')");
        try {
            assertThatThrownBy(() -> service.createCommunity(
                    "롤백 대상", GameType.BATTLEGROUNDS_STEAM, user.getId()
            )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(communities.count()).isZero();
            assertThat(communityGames.count()).isZero();
            assertThat(memberships.count()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE community_games DROP CONSTRAINT reject_steam");
        }
    }

    @Test
    void rejectsInvalidCommunityNames() throws Exception {
        mvc.perform(post("/api/communities").session(session).contentType("application/json")
                .content("{\"name\":\"  \"}")).andExpect(status().isBadRequest());
        assertThat(communities.count()).isZero();
    }

    @Test
    void dashboardReportsDisconnectedAndConnectedState() throws Exception {
        Community mine = service.createCommunity("치즈 클랜", user.getId());
        String path = "/api/communities/" + mine.getId();
        mvc.perform(get(path).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.discordConnected").value(false))
                .andExpect(jsonPath("$.gameType").value("BATTLEGROUNDS_KAKAO"))
                .andExpect(jsonPath("$.gameName").value("배틀그라운드 카카오"));
        connections.save(new DiscordCommunityConnection(mine, "123456", "Cheeeze"));
        mvc.perform(get(path).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.discordConnected").value(true))
                .andExpect(jsonPath("$.discordGuildName").value("Cheeeze"));
        verifyNoInteractions(jda);
    }

    @Test
    void deniesAllForeignCommunityPathsBeforeCallingDiscord() throws Exception {
        Community foreign = service.createCommunity("비공개", other.getId());
        String base = "/api/communities/" + foreign.getId();
        for (String suffix : new String[]{"", "/members", "/discord/roles", "/discord/roles/123/members",
                "/discord/oauth/authorize", "/discord/oauth/results/unknown"}) {
            mvc.perform(get(base + suffix).session(session)).andExpect(status().isForbidden());
        }
        mvc.perform(post(base + "/members").session(session).contentType("application/json")
                .content("{\"nickname\":\"침입\"}")).andExpect(status().isForbidden());
        mvc.perform(put(base + "/member-role-settings").session(session).contentType("application/json")
                .content("{\"discordRoleIds\":[\"123\"]}")).andExpect(status().isForbidden());
        mvc.perform(post(base + "/discord/bot-install/authorize").session(session).contentType("application/json")
                .content("{\"oauthResultId\":\"test\",\"guildId\":\"123\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(discord, jda);
    }

    @Test
    void blocksGuildIdBypassAndForeignInstallToken() throws Exception {
        Community foreign = service.createCommunity("비공개", other.getId());
        connections.save(new DiscordCommunityConnection(foreign, "123456", "Hidden"));
        mvc.perform(get("/api/discord/guilds/123456/roles").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/discord/guilds/123456/roles/789/members").session(session))
                .andExpect(status().isForbidden());
        String token = installs.createInstallToken(new DiscordBotInstallSession(foreign.getId(), "123456", "Hidden"));
        mvc.perform(post("/api/discord/bot-install/confirm").session(session).contentType("application/json")
                .content("{\"installToken\":\"" + token + "\"}")).andExpect(status().isForbidden());
        assertThat(installs.getInstallSession(token)).isNotNull();
        verifyNoInteractions(jda);
    }

    @Test
    void callbackRequiresInitiatingSessionAndRechecksMembership() throws Exception {
        Community mine = service.createCommunity("내 서버", user.getId());
        String url = mvc.perform(get("/api/communities/" + mine.getId() + "/discord/oauth/authorize")
                .session(session)).andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        String state = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");
        mvc.perform(get("/api/discord/oauth/callback").param("code", "code").param("state", state))
                .andExpect(status().isUnauthorized());
        MockHttpSession differentSession = new MockHttpSession();
        differentSession.setAttribute("LOGIN_USER_ID", user.getId());
        mvc.perform(get("/api/discord/oauth/callback").session(differentSession)
                .param("code", "code").param("state", state)).andExpect(status().isBadRequest());
        memberships.deleteAll();
        mvc.perform(get("/api/discord/oauth/callback").session(session)
                .param("code", "code").param("state", state)).andExpect(status().isForbidden());
        verifyNoInteractions(discord);
    }

    @Test
    void authorizedMemberCanUseManualMemberManagement() throws Exception {
        Community mine = service.createCommunity("치즈", user.getId());
        String path = "/api/communities/" + mine.getId() + "/members";
        mvc.perform(post(path).session(session).contentType("application/json")
                .content("{\"nickname\":\"클랜원\"}")).andExpect(status().isCreated());
        mvc.perform(get(path).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nickname").value("클랜원"));
    }
    @Test
    void preservesOAuthGuildSelectionBotInstallationAndRoleQueries() throws Exception {
        Community mine = service.createCommunity("치즈", user.getId());
        String base = "/api/communities/" + mine.getId();
        String authorization = mvc.perform(get(base + "/discord/oauth/authorize").session(session))
                .andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        String state = UriComponentsBuilder.fromUriString(authorization).build().getQueryParams().getFirst("state");
        org.mockito.Mockito.when(discord.exchangeCode("code")).thenReturn(
                new com.guildup.discord.oauth.client.dto.DiscordAccessTokenResponse("token", "Bearer", 3600, "identify guilds"));
        org.mockito.Mockito.when(discord.getCurrentUser("token")).thenReturn(
                new com.guildup.discord.oauth.client.dto.DiscordApiUser("999", "user", "User", null));
        org.mockito.Mockito.when(discord.getCurrentUserGuilds("token")).thenReturn(java.util.List.of(
                new com.guildup.discord.oauth.client.dto.DiscordApiGuild("123456", "Cheeeze", null, true, "0")));
        String callback = mvc.perform(get("/api/discord/oauth/callback").session(session)
                        .param("code", "code").param("state", state))
                .andExpect(status().isFound()).andReturn().getResponse().getHeader("Location");
        String result = UriComponentsBuilder.fromUriString(callback).build().getQueryParams().getFirst("oauthResult");
        mvc.perform(get(base + "/discord/oauth/results/" + result).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.guilds[0].id").value("123456"));
        mvc.perform(post(base + "/discord/guild-selection/inspect").session(session)
                        .contentType("application/json")
                        .content("{\"oauthResultId\":\"" + result + "\",\"guildId\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyConnected").value(false));
        String installResponse = mvc.perform(post(base + "/discord/bot-install/authorize").session(session)
                        .contentType("application/json")
                        .content("{\"oauthResultId\":\"" + result + "\",\"guildId\":\"123456\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.alreadyInstalled").value(false))
                .andReturn().getResponse().getContentAsString();
        String token = new tools.jackson.databind.ObjectMapper().readTree(installResponse).get("installToken").asText();
        var guild = org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.Guild.class);
        var role = org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.Role.class);
        org.mockito.Mockito.when(jda.getGuildById("123456")).thenReturn(guild);
        org.mockito.Mockito.when(guild.getId()).thenReturn("123456");
        org.mockito.Mockito.when(guild.getName()).thenReturn("Cheeeze");
        org.mockito.Mockito.when(guild.getRoles()).thenReturn(java.util.List.of(role));
        org.mockito.Mockito.when(role.getId()).thenReturn("789");
        org.mockito.Mockito.when(role.getName()).thenReturn("클랜원");
        org.mockito.Mockito.when(guild.getRoleById("789")).thenReturn(role);
        org.mockito.Mockito.when(guild.getMembersWithRoles(role)).thenReturn(java.util.List.of());
        mvc.perform(post("/api/discord/bot-install/confirm").session(session).contentType("application/json")
                        .content("{\"installToken\":\"" + token + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.connected").value(true));
        mvc.perform(get(base).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.discordGuildName").value("Cheeeze"));
        mvc.perform(get(base + "/discord/roles").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("789"));
        mvc.perform(get(base + "/discord/roles/789/members").session(session))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void reportsGuildConnectionConflictWithoutChangingExistingConnection() throws Exception {
        Community original = service.createCommunity("기존 커뮤니티", other.getId());
        Community selected = service.createCommunity("새 커뮤니티", user.getId());
        connections.save(new DiscordCommunityConnection(original, "123456", "Cheeeze"));
        var guild = org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.Guild.class);
        org.mockito.Mockito.when(jda.getGuildById("123456")).thenReturn(guild);
        org.mockito.Mockito.when(guild.getId()).thenReturn("123456");
        org.mockito.Mockito.when(guild.getName()).thenReturn("Cheeeze");
        String resultId = oauthResults.saveResult(selected.getId(),
                new com.guildup.discord.oauth.dto.DiscordOAuthResultResponse(null, java.util.List.of(
                        new com.guildup.discord.oauth.dto.DiscordManageableGuildResponse("123456", "Cheeeze", null, true))));

        mvc.perform(post("/api/communities/" + selected.getId() + "/discord/bot-install/authorize")
                        .session(session).contentType("application/json")
                        .content("{\"oauthResultId\":\"" + resultId + "\",\"guildId\":\"123456\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("DISCORD_GUILD_ALREADY_CONNECTED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("이미 다른 커뮤니티")));

        String token = installs.createInstallToken(new DiscordBotInstallSession(selected.getId(), "123456", "Cheeeze"));
        mvc.perform(post("/api/discord/bot-install/confirm").session(session).contentType("application/json")
                        .content("{\"installToken\":\"" + token + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DISCORD_GUILD_ALREADY_CONNECTED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("이미 다른 커뮤니티")));
        assertThat(connections.findByCommunityId(original.getId())).isPresent();
        assertThat(connections.findByCommunityId(selected.getId())).isEmpty();
        assertThat(connections.count()).isEqualTo(1);
    }

    @Test
    void findsExistingGuildAndLetsAnotherDiscordManagerJoinAsAdmin() throws Exception {
        Community existing = service.createCommunity("치즈클랜", other.getId());
        connections.saveAndFlush(new DiscordCommunityConnection(existing, "123456", "치즈클랜 Discord"));
        Community source = service.createCommunity("임시 치즈클랜", user.getId());
        String resultId = oauthResults.saveResult(source.getId(),
                new com.guildup.discord.oauth.dto.DiscordOAuthResultResponse(null, java.util.List.of(
                        new com.guildup.discord.oauth.dto.DiscordManageableGuildResponse(
                                "123456", "치즈클랜 Discord", null, false
                        ))));
        String base = "/api/communities/" + source.getId() + "/discord/guild-selection";
        String body = "{\"oauthResultId\":\"" + resultId + "\",\"guildId\":\"123456\"}";

        mvc.perform(post(base + "/inspect").session(session).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyConnected").value(true))
                .andExpect(jsonPath("$.communityId").value(existing.getId()))
                .andExpect(jsonPath("$.communityName").value("치즈클랜"))
                .andExpect(jsonPath("$.alreadyMember").value(false));
        mvc.perform(post(base + "/join").session(session).contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.communityId").value(existing.getId()))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        assertThat(memberships.findByCommunityIdAndUserId(existing.getId(), user.getId()))
                .get().extracting(CommunityUser::getRole).isEqualTo(CommunityUserRole.ADMIN);
        assertThat(communities.findById(source.getId())).isEmpty();
        assertThat(memberships.findByCommunityId(existing.getId())).hasSize(2);
    }

    @Test
    void reportsAlreadyMemberAndPreventsDuplicateCommunityUserRows() throws Exception {
        Community existing = service.createCommunity("치즈클랜", other.getId());
        connections.saveAndFlush(new DiscordCommunityConnection(existing, "123456", "치즈클랜 Discord"));
        memberships.saveAndFlush(new CommunityUser(existing, user, CommunityUserRole.ADMIN));
        Community source = service.createCommunity("새 공간", user.getId());
        String resultId = oauthResults.saveResult(source.getId(),
                new com.guildup.discord.oauth.dto.DiscordOAuthResultResponse(null, java.util.List.of(
                        new com.guildup.discord.oauth.dto.DiscordManageableGuildResponse("123456", "Discord", null, true))));
        String base = "/api/communities/" + source.getId() + "/discord/guild-selection";
        String body = "{\"oauthResultId\":\"" + resultId + "\",\"guildId\":\"123456\"}";

        mvc.perform(post(base + "/inspect").session(session).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.alreadyMember").value(true));
        mvc.perform(post(base + "/join").session(session).contentType("application/json").content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_COMMUNITY_MEMBER"))
                .andExpect(jsonPath("$.communityId").value(existing.getId()));
        assertThat(memberships.findByCommunityId(existing.getId())).hasSize(2);
        assertThat(communities.findById(source.getId())).isPresent();

        String retryResultId = oauthResults.saveResult(source.getId(),
                new com.guildup.discord.oauth.dto.DiscordOAuthResultResponse(null, java.util.List.of(
                        new com.guildup.discord.oauth.dto.DiscordManageableGuildResponse("123456", "Discord", null, true))));
        mvc.perform(post(base + "/join").session(session).contentType("application/json")
                        .content("{\"oauthResultId\":\"" + retryResultId
                                + "\",\"guildId\":\"123456\",\"discardSourceCommunity\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.communityId").value(existing.getId()))
                .andExpect(jsonPath("$.role").value("ADMIN"));
        assertThat(communities.findById(source.getId())).isEmpty();
    }

    @Test
    void rejectsJoinWhenGuildWasNotInManageableOAuthGuilds() throws Exception {
        Community existing = service.createCommunity("치즈클랜", other.getId());
        connections.saveAndFlush(new DiscordCommunityConnection(existing, "123456", "Discord"));
        Community source = service.createCommunity("새 공간", user.getId());
        String resultId = oauthResults.saveResult(source.getId(),
                new com.guildup.discord.oauth.dto.DiscordOAuthResultResponse(null, java.util.List.of()));

        mvc.perform(post("/api/communities/" + source.getId() + "/discord/guild-selection/join")
                        .session(session).contentType("application/json")
                        .content("{\"oauthResultId\":\"" + resultId + "\",\"guildId\":\"123456\"}"))
                .andExpect(status().isBadRequest());
        assertThat(memberships.findByCommunityIdAndUserId(existing.getId(), user.getId())).isEmpty();
    }

    @Test
    void databaseConstraintsRejectDuplicateGuildAndDuplicateMembership() {
        Community first = service.createCommunity("첫 커뮤니티", user.getId());
        Community second = service.createCommunity("두 번째 커뮤니티", other.getId());
        connections.saveAndFlush(new DiscordCommunityConnection(first, "123456", "Discord"));

        assertThatThrownBy(() -> connections.saveAndFlush(
                new DiscordCommunityConnection(second, "123456", "Discord")
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> memberships.saveAndFlush(
                new CommunityUser(first, user, CommunityUserRole.ADMIN)
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void storesMultipleRoleSettingsDeduplicatesIdsAndRestoresSelection() throws Exception {
        Community mine = service.createCommunity("치즈", user.getId());
        connections.save(new DiscordCommunityConnection(mine, "123456", "Cheeeze"));
        var guild = mockGuild("123456");
        var memberRole = mockRole("789", "클랜원", 2);
        var adminRole = mockRole("456", "운영진", 1);
        org.mockito.Mockito.when(guild.getRoles()).thenReturn(java.util.List.of(memberRole, adminRole));
        String path = "/api/communities/" + mine.getId() + "/member-role-settings";

        mvc.perform(put(path).session(session).contentType("application/json")
                        .content("{\"discordRoleIds\":[\"789\",\"456\",\"789\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(2))
                .andExpect(jsonPath("$.roles[0].discordRoleName").value("클랜원"));
        mvc.perform(get(path).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].discordRoleId").value("789"))
                .andExpect(jsonPath("$.roles[1].discordRoleId").value("456"));
    }

    @Test
    void rejectsUnknownRoleWithoutReplacingExistingSettings() throws Exception {
        Community mine = service.createCommunity("치즈", user.getId());
        connections.save(new DiscordCommunityConnection(mine, "123456", "Cheeeze"));
        var guild = mockGuild("123456");
        var role = mockRole("789", "클랜원", 1);
        org.mockito.Mockito.when(guild.getRoles()).thenReturn(java.util.List.of(role));
        memberRoleSettings.save(new CommunityMemberRoleSetting(mine, "789", "클랜원"));
        String path = "/api/communities/" + mine.getId() + "/member-role-settings";

        mvc.perform(put(path).session(session).contentType("application/json")
                        .content("{\"discordRoleIds\":[\"missing\"]}"))
                .andExpect(status().isBadRequest());
        assertThat(memberRoleSettings.findByCommunityIdOrderByIdAsc(mine.getId()))
                .extracting(CommunityMemberRoleSetting::getDiscordRoleId)
                .containsExactly("789");
    }

    @Test
    void memberCanReadButCannotChangeRoleSettingsOrRunSync() throws Exception {
        Community community = service.createCommunity("공유", other.getId());
        memberships.save(new CommunityUser(community, user, CommunityUserRole.MEMBER));
        connections.save(new DiscordCommunityConnection(community, "123456", "Cheeeze"));
        memberRoleSettings.save(new CommunityMemberRoleSetting(community, "789", "클랜원"));
        String base = "/api/communities/" + community.getId();

        mvc.perform(get(base + "/member-role-settings").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles.length()").value(1));
        mvc.perform(put(base + "/member-role-settings").session(session).contentType("application/json")
                        .content("{\"discordRoleIds\":[]}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(base + "/members/sync").session(session))
                .andExpect(status().isForbidden());
        verifyNoInteractions(jda);
    }

    @Test
    void memberCannotStartDiscordConnectionOrAddManualMembers() throws Exception {
        Community community = service.createCommunity("공유", other.getId());
        memberships.save(new CommunityUser(community, user, CommunityUserRole.MEMBER));
        String base = "/api/communities/" + community.getId();

        mvc.perform(get(base + "/discord/oauth/authorize").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(post(base + "/members").session(session).contentType("application/json")
                        .content("{\"nickname\":\"권한 없는 추가\"}"))
                .andExpect(status().isForbidden());
        assertThat(communityMembers.findByCommunityIdAndStatusOrderByIdAsc(
                community.getId(), CommunityMemberStatus.ACTIVE
        )).isEmpty();
        verifyNoInteractions(discord, jda);
    }

    @Test
    void ownerSynchronizesDiscordMemberReadsItAndDoesNotDuplicateIt() throws Exception {
        Community mine = service.createCommunity("치즈", user.getId());
        DiscordCommunityConnection connection = connections.save(
                new DiscordCommunityConnection(mine, "123456", "Cheeeze"));
        memberRoleSettings.save(new CommunityMemberRoleSetting(mine, "789", "클랜원"));
        var guild = mockGuild("123456");
        var role = mockRole("789", "클랜원", 1);
        var discordMember = mockDiscordMember("999", "apple", "애플", role);
        org.mockito.Mockito.when(guild.getMembers()).thenReturn(java.util.List.of(discordMember));
        String base = "/api/communities/" + mine.getId();

        mvc.perform(post(base + "/members/sync").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedMembers").value(1))
                .andExpect(jsonPath("$.createdMembers").value(1));
        mvc.perform(get(base + "/members").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].discordUserId").value("999"))
                .andExpect(jsonPath("$[0].discordDisplayName").value("애플"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        mvc.perform(post(base + "/members/sync").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.createdMembers").value(0));
        assertThat(communityMembers.count()).isEqualTo(1);
        assertThat(memberAccounts.count()).isEqualTo(1);
        assertThat(connections.findById(connection.getId()).orElseThrow().getLastMemberSyncedAt()).isNotNull();
        mvc.perform(get(base).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lastMemberSyncedAt").isNotEmpty());
    }

    @Test
    void adminCanRunMemberSync() throws Exception {
        Community community = service.createCommunity("공유", other.getId());
        memberships.save(new CommunityUser(community, user, CommunityUserRole.ADMIN));
        connections.save(new DiscordCommunityConnection(community, "123456", "Cheeeze"));
        memberRoleSettings.save(new CommunityMemberRoleSetting(community, "789", "클랜원"));
        var guild = mockGuild("123456");
        org.mockito.Mockito.when(guild.getMembers()).thenReturn(java.util.List.of());

        mvc.perform(post("/api/communities/" + community.getId() + "/members/sync").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matchedMembers").value(0));
    }

    @Test
    void ownerPreviewsSavesAndReadsGameNicknameRule() throws Exception {
        Community mine = service.createCommunity("치즈", user.getId());
        connections.save(new DiscordCommunityConnection(mine, "123456", "Cheeeze"));
        userExternalAccounts.save(new com.guildup.user.domain.UserExternalAccount(
                user, com.guildup.account.domain.ExternalAccountProvider.DISCORD, "999", "apple"
        ));
        var guild = mockGuild("123456");
        var currentMember = mockDiscordMember("999", "apple", "애플(93) sa-gwa");
        var otherMember = mockDiscordMember("888", "julmi", "절미(95) jul-mi");
        var unmatchedMember = mockDiscordMember("777", "potato", "감자");
        org.mockito.Mockito.when(guild.getMembers())
                .thenReturn(java.util.List.of(currentMember, otherMember, unmatchedMember));
        String path = "/api/communities/" + mine.getId() + "/game-nickname-rule";

        mvc.perform(post(path + "/preview").session(session).contentType("application/json")
                        .content("{\"gameNickname\":\"sa-gwa\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successfulMembers").value(2))
                .andExpect(jsonPath("$.failedMembers").value(1))
                .andExpect(jsonPath("$.members[2].status").value("NEEDS_REVIEW"));
        mvc.perform(put(path).session(session).contentType("application/json")
                        .content("{\"gameNickname\":\"sa-gwa\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true));
        mvc.perform(get(path).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleGameNickname").value("sa-gwa"))
                .andExpect(jsonPath("$.sampleDiscordNickname").value("애플(93) sa-gwa"));
        mvc.perform(get(path + "/preview").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.successfulMembers").value(2))
                .andExpect(jsonPath("$.failedMembers").value(1))
                .andExpect(jsonPath("$.successRate").value(66.7));
    }

    @Test
    void memberCannotPreviewOrSaveGameNicknameRule() throws Exception {
        Community community = service.createCommunity("공유", other.getId());
        memberships.save(new CommunityUser(community, user, CommunityUserRole.MEMBER));
        String path = "/api/communities/" + community.getId() + "/game-nickname-rule";

        mvc.perform(get(path).session(session))
                .andExpect(status().isForbidden());
        mvc.perform(post(path + "/preview").session(session).contentType("application/json")
                        .content("{\"gameNickname\":\"sa-gwa\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(path).session(session).contentType("application/json")
                        .content("{\"gameNickname\":\"sa-gwa\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(path + "/preview").session(session))
                .andExpect(status().isForbidden());
        verifyNoInteractions(jda);
    }

    private net.dv8tion.jda.api.entities.Guild mockGuild(String id) {
        var guild = org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.Guild.class);
        org.mockito.Mockito.when(jda.getGuildById(id)).thenReturn(guild);
        return guild;
    }

    private net.dv8tion.jda.api.entities.Role mockRole(String id, String name, int position) {
        var role = org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.Role.class);
        org.mockito.Mockito.when(role.getId()).thenReturn(id);
        org.mockito.Mockito.when(role.getName()).thenReturn(name);
        org.mockito.Mockito.when(role.getPosition()).thenReturn(position);
        org.mockito.Mockito.when(role.isPublicRole()).thenReturn(false);
        return role;
    }

    @SuppressWarnings("deprecation")
    private net.dv8tion.jda.api.entities.Member mockDiscordMember(
            String id,
            String username,
            String displayName,
            net.dv8tion.jda.api.entities.Role role
    ) {
        var member = org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.Member.class);
        var discordUser = org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.User.class);
        org.mockito.Mockito.when(member.getUser()).thenReturn(discordUser);
        org.mockito.Mockito.when(discordUser.getId()).thenReturn(id);
        org.mockito.Mockito.when(discordUser.getName()).thenReturn(username);
        org.mockito.Mockito.when(discordUser.isBot()).thenReturn(false);
        org.mockito.Mockito.when(member.getNickname()).thenReturn(displayName);
        org.mockito.Mockito.when(member.getRoles()).thenReturn(java.util.List.of(role));
        org.mockito.Mockito.when(member.hasTimeJoined()).thenReturn(false);
        return member;
    }

    @SuppressWarnings("deprecation")
    private net.dv8tion.jda.api.entities.Member mockDiscordMember(
            String id,
            String username,
            String displayName
    ) {
        var member = org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.Member.class);
        var discordUser = org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.User.class);
        org.mockito.Mockito.when(member.getUser()).thenReturn(discordUser);
        org.mockito.Mockito.when(discordUser.getId()).thenReturn(id);
        org.mockito.Mockito.when(discordUser.getName()).thenReturn(username);
        org.mockito.Mockito.when(discordUser.isBot()).thenReturn(false);
        org.mockito.Mockito.when(member.getNickname()).thenReturn(displayName);
        return member;
    }

}
