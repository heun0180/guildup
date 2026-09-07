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
    @Autowired UserRepository users;
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
        connections.deleteAll();
        memberships.deleteAll();
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
                    .andExpect(jsonPath("$[0].role").value("OWNER"));
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
                        .content("{\"name\":\"  치즈 클랜  \",\"userId\":" + other.getId() + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("치즈 클랜"));
        var list = memberships.findByUserIdOrderByCommunityIdAsc(user.getId());
        assertThat(list).hasSize(1);
        assertThat(list.getFirst().getRole()).isEqualTo(CommunityUserRole.OWNER);
        assertThat(memberships.findByUserIdOrderByCommunityIdAsc(other.getId())).isEmpty();
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
        } finally {
            jdbc.execute("ALTER TABLE community_users DROP CONSTRAINT reject_owner");
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
                .andExpect(jsonPath("$.discordConnected").value(false));
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
        mvc.perform(put(base + "/discord-member-role").session(session).contentType("application/json")
                .content("{\"roleId\":\"123\"}")).andExpect(status().isForbidden());
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
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("이미 다른 커뮤니티")));

        String token = installs.createInstallToken(new DiscordBotInstallSession(selected.getId(), "123456", "Cheeeze"));
        mvc.perform(post("/api/discord/bot-install/confirm").session(session).contentType("application/json")
                        .content("{\"installToken\":\"" + token + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("이미 다른 커뮤니티")));
        assertThat(connections.findByCommunityId(original.getId())).isPresent();
        assertThat(connections.findByCommunityId(selected.getId())).isEmpty();
        assertThat(connections.count()).isEqualTo(1);
    }

}
