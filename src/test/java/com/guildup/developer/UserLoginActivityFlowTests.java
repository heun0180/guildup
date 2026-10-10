package com.guildup.developer;

import com.guildup.developer.service.DeveloperUserQueryService;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.discord.oauth.client.DiscordApiClient;
import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import com.guildup.user.auth.service.*;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:login-activity;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.show-sql=false", "discord.oauth.client-id=test-client", "discord.oauth.client-secret=test-secret"
})
@AutoConfigureMockMvc
class UserLoginActivityFlowTests {
    @Autowired MockMvc mvc;
    @Autowired DeveloperUserQueryService queries;
    @Autowired com.guildup.user.repository.UserRepository users;
    @MockitoSpyBean Clock clock;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    @MockitoBean DiscordApiClient discord;
    final ObjectMapper json = new ObjectMapper();
    final Instant now = Instant.parse("2026-10-10T03:00:00Z");
    @BeforeEach void setup() { when(clock.instant()).thenReturn(now); }
    @Test void successfulEmailAndDiscordAuthenticationRecordsLoginButAccountLinkDoesNot() throws Exception {
        MockHttpSession signup = new MockHttpSession();
        String csrf = SessionCsrfTokens.getOrCreate(signup);
        var created = mvc.perform(post("/api/auth/signup").session(signup).header("X-CSRF-Token", csrf).contentType("application/json")
                        .content("{\"email\":\"new-login@example.com\",\"password\":\"GuildUp123!\",\"passwordConfirmation\":\"GuildUp123!\",\"nickname\":\"신규\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        long id = json.readTree(created.getContentAsString()).get("id").asLong();
        assertThat(queries.user(id).user().lastLoginAt()).isEqualTo(now);
        when(clock.instant()).thenReturn(now.plusSeconds(1200));
        MockHttpSession failed = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(failed).header("X-CSRF-Token", SessionCsrfTokens.getOrCreate(failed)).contentType("application/json")
                .content("{\"email\":\"new-login@example.com\",\"password\":\"Wrong123!\"}")).andExpect(status().isUnauthorized());
        assertThat(queries.user(id).user().lastLoginAt()).isEqualTo(now);
        MockHttpSession login = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(login).header("X-CSRF-Token", SessionCsrfTokens.getOrCreate(login)).contentType("application/json")
                .content("{\"email\":\"new-login@example.com\",\"password\":\"GuildUp123!\"}")).andExpect(status().isOk());
        assertThat(queries.user(id).user().lastLoginAt()).isEqualTo(now.plusSeconds(1200));
        when(discord.getCurrentUser(anyString())).thenReturn(new DiscordApiUser("new-discord", "discord-user", "Discord 닉네임", null));
        when(discord.exchangeCode(anyString(), anyString())).thenReturn(new com.guildup.discord.oauth.client.dto.DiscordAccessTokenResponse("TEST_TOKEN", "Bearer", 60, "identify"));
        login.setAttribute(DiscordAuthAttempt.ATTRIBUTE, new DiscordAuthAttempt("state", "http://localhost/callback", DiscordAuthAttempt.Purpose.LINK_ACCOUNT, id, Instant.now()));
        mvc.perform(get("/api/auth/discord/callback").param("code", "code").param("state", "state").session(login)).andExpect(status().isFound());
        assertThat(queries.user(id).user().lastLoginAt()).isEqualTo(now.plusSeconds(1200));
        assertThat(queries.user(id).loginAccounts()).hasSize(2);
        when(clock.instant()).thenReturn(now.plusSeconds(2400));
        MockHttpSession oauth = new MockHttpSession();
        oauth.setAttribute(DiscordAuthAttempt.ATTRIBUTE, new DiscordAuthAttempt("state", "http://localhost/callback", DiscordAuthAttempt.Purpose.LOGIN, null, Instant.now()));
        mvc.perform(get("/api/auth/discord/callback").param("code", "code").param("state", "state").session(oauth)).andExpect(status().isFound());
        assertThat(queries.user(id).user().lastLoginAt()).isEqualTo(now.plusSeconds(2400));
        when(clock.instant()).thenReturn(now.plusSeconds(3600));
        mvc.perform(get("/api/auth/me").session(oauth)).andExpect(status().isOk());
        assertThat(queries.user(id).user().lastActiveAt()).isEqualTo(now.plusSeconds(3600));
        assertThat(queries.user(id).user().lastLoginAt()).isEqualTo(now.plusSeconds(2400));
        when(clock.instant()).thenReturn(now.plusSeconds(3660));
        mvc.perform(get("/api/auth/me").session(oauth)).andExpect(status().isOk());
        assertThat(queries.user(id).user().lastActiveAt()).isEqualTo(now.plusSeconds(3600));
    }

    @Test void addingEmailToDiscordUserDoesNotRecordAnotherLoginAndProfileSaveKeepsActivity() throws Exception {
        when(discord.getCurrentUser(anyString())).thenReturn(new DiscordApiUser("email-link-discord", "discord-user", "Discord 회원", null));
        when(discord.exchangeCode(anyString(), anyString())).thenReturn(new com.guildup.discord.oauth.client.dto.DiscordAccessTokenResponse("TEST_TOKEN", "Bearer", 60, "identify"));
        var session = new MockHttpSession();
        session.setAttribute(DiscordAuthAttempt.ATTRIBUTE, new DiscordAuthAttempt("state", "http://localhost/callback", DiscordAuthAttempt.Purpose.LOGIN, null, Instant.now()));
        mvc.perform(get("/api/auth/discord/callback").param("code", "code").param("state", "state").session(session)).andExpect(status().isFound());
        long id = CurrentUserSession.requireUserId(session);
        var stale = users.findById(id).orElseThrow();
        when(clock.instant()).thenReturn(now.plusSeconds(1200));
        mvc.perform(post("/api/auth/credentials").session(session).header("X-CSRF-Token", SessionCsrfTokens.getOrCreate(session)).contentType("application/json")
                .content("{\"email\":\"added-to-discord@example.com\",\"password\":\"GuildUp123!\",\"passwordConfirmation\":\"GuildUp123!\"}"))
                .andExpect(status().isCreated());
        assertThat(queries.user(id).user().lastLoginAt()).isEqualTo(now);
        assertThat(queries.user(id).user().lastActiveAt()).isEqualTo(now.plusSeconds(1200));
        assertThat(queries.user(id).loginAccounts()).hasSize(2);
        stale.updateProfile("변경된 닉네임", null); users.saveAndFlush(stale);
        assertThat(queries.user(id).user().lastLoginAt()).isEqualTo(now);
        assertThat(queries.user(id).user().lastActiveAt()).isEqualTo(now.plusSeconds(1200));
    }

}
