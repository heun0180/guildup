package com.guildup.user.auth;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;
import com.guildup.community.repository.CommunityUserRepository;
import com.guildup.community.service.CommunityService;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:session-csrf;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "discord.oauth.client-id=test-client", "discord.oauth.client-secret=test-secret"
})
@AutoConfigureMockMvc
@Transactional
class SessionCsrfFlowTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired CommunityService communities;
    @Autowired CommunityUserRepository memberships;
    @Autowired com.guildup.community.repository.CommunityGameRepository games;
    @Autowired com.guildup.user.repository.UserExternalAccountRepository userAccounts;
    @Autowired com.guildup.community.repository.CommunityMemberRepository members;
    @Autowired com.guildup.community.repository.CommunityMemberAccountRepository memberAccounts;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    MockHttpSession session;
    Community community;
    User member;

    @BeforeEach
    void setUp() {
        User owner = users.save(new User("csrf-owner"));
        member = users.save(new User("csrf-member"));
        community = communities.createCommunity("csrf-test", owner.getId());
        var discord = com.guildup.account.domain.ExternalAccountProvider.DISCORD;
        userAccounts.save(new com.guildup.user.domain.UserExternalAccount(owner, discord, "csrf-owner-id", "owner"));
        var clanMember = members.save(new com.guildup.community.domain.CommunityMember(community, "owner"));
        memberAccounts.save(new com.guildup.community.domain.CommunityMemberAccount(clanMember, discord, "csrf-owner-id", "owner"));
        memberships.save(new CommunityUser(community, member, CommunityUserRole.MEMBER));
        session = loginSession(owner);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void rejectsMissingTokenForEveryMutationMethod(String method) throws Exception {
        mvc.perform(mutation(method).session(session))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_MISSING"));
        assertThat(session.isInvalid()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void acceptsSessionTokenForEveryMutationMethod(String method) throws Exception {
        mvc.perform(mutation(method).session(session).header("X-CSRF-Token", token(session)))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void wrongTokenCannotLogoutOrInvalidateSession() throws Exception {
        token(session);
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-Token", "wrong"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        assertThat(session.isInvalid()).isFalse();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void tokenFromAnotherSessionCannotLogout() throws Exception {
        MockHttpSession other = loginSession(member);
        String otherToken = token(other);
        assertThat(token(session)).isNotEqualTo(otherToken);
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-Token", otherToken))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void validTokenLogsOutAndSessionTokensAreNotCached() throws Exception {
        String token = token(session);
        assertThat(token(session)).isEqualTo(token);
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-Token", token))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void readAndPublicEndpointsAndAnonymousAuthenticationKeepWorking() throws Exception {
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/communities/" + community.getId()).session(session)).andExpect(status().isOk());
        mvc.perform(head("/api/auth/me").session(session)).andExpect(status().isOk());
        mvc.perform(get("/login.html")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/api/communities/" + community.getId() + "/attendance"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/discord/callback").param("code", "code").param("state", "wrong"))
                .andExpect(redirectedUrl("/login.html?oauthError=session"));
    }

    @Test
    void tokenDoesNotGrantMemberManagementOrAdminOwnerRights() throws Exception {
        MockHttpSession memberSession = loginSession(member);
        mvc.perform(put("/api/communities/" + community.getId() + "/ranking-settings")
                        .session(memberSession).header("X-CSRF-Token", token(memberSession))
                        .contentType("application/json").content("{\"periodType\":\"MONTHLY\"}"))
                .andExpect(status().isForbidden());
        var membership = memberships.findByCommunityIdAndUserId(community.getId(), member.getId()).orElseThrow();
        membership.changeRole(CommunityUserRole.ADMIN);
        mvc.perform(put("/api/communities/" + community.getId() + "/ranking-settings")
                        .session(memberSession).header("X-CSRF-Token", token(memberSession))
                        .contentType("application/json").content("{\"periodType\":\"MONTHLY\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/communities/" + community.getId()).session(memberSession)
                        .header("X-CSRF-Token", token(memberSession)))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectsAllFeatureMutationRoutesIncludingThoseOutsideCommunityInterceptor() throws Exception {
        String base = "/api/communities/" + community.getId();
        String game = base + "/games/" + games.findByCommunityIdOrderByIdAsc(community.getId()).getFirst().getId();
        String bingo = game + "/bingos";
        String kill = game + "/kill-competitions";
        for (var request : java.util.List.of(
                post("/api/communities"), post(base + "/attendance"), delete(base),
                put(base + "/ranking-settings"), put(base + "/member-role-settings"),
                patch(base + "/users/" + member.getId() + "/role"),
                post(base + "/members"), delete(base + "/members/1"), post(base + "/members/sync"),
                post(base + "/posts"), patch(base + "/posts/1"), delete(base + "/posts/1"),
                post(base + "/posts/1/comments"), patch(base + "/posts/1/comments/1"), delete(base + "/posts/1/comments/1"),
                post(base + "/notices"), put(base + "/notices/1"), delete(base + "/notices/1"),
                post(base + "/events"), put(base + "/events/1"), delete(base + "/events/1"),
                post(bingo), patch(bingo + "/1"), delete(bingo + "/1"),
                post(bingo + "/1/aggregate"), post(bingo + "/1/aggregate/me"),
                post(bingo + "/temporary-rebuild/preview"), post(bingo + "/temporary-rebuild/apply"),
                post(kill), put(kill + "/1/recruitment"), post(kill + "/1/start"), delete(kill + "/1"),
                post(kill + "/1/interim"), post(kill + "/1/finalize"), post(kill + "/1/result-request"),
                post(base + "/discord/dm"), post(base + "/discord/bot-install/authorize"),
                post("/api/discord/bot-install/confirm"), post(base + "/discord/guild-selection/inspect"),
                post(base + "/discord/guild-selection/join"),
                post(game + "/nickname-rule/sync"), put(game + "/nickname-rule"),
                post(game + "/nickname-rule/preview"), post(game + "/activities/sync"), put(game + "/activity-rule"),
                post(game + "/team-maker/generate"), post(game + "/team-maker/rebalance"),
                post("/api/community-discoveries/discord/" + community.getId() + "/join"),
                post(base + "/feedback"), post("/api/auth/logout"))) {
            mvc.perform(request.session(session)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_TOKEN_MISSING"));
        }
        org.mockito.Mockito.verifyNoInteractions(jda);
    }

    private MockHttpServletRequestBuilder mutation(String method) {
        String base = "/api/communities/" + community.getId();
        return switch (method) {
            case "POST" -> post(base + "/attendance");
            case "PUT" -> put(base + "/ranking-settings").contentType("application/json")
                    .content("{\"periodType\":\"MONTHLY\"}");
            case "PATCH" -> patch(base + "/users/" + member.getId() + "/role").contentType("application/json")
                    .content("{\"role\":\"ADMIN\"}");
            case "DELETE" -> delete(base);
            default -> throw new IllegalArgumentException(method);
        };
    }

    private String token(MockHttpSession target) throws Exception {
        String body = mvc.perform(get("/api/auth/csrf").session(target))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(body).get("token").asText();
    }

    private MockHttpSession loginSession(User user) {
        MockHttpSession result = new MockHttpSession();
        result.setAttribute("LOGIN_USER_ID", user.getId());
        return result;
    }
}
