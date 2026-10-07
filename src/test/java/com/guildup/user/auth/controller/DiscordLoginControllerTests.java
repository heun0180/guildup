package com.guildup.user.auth.controller;

import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import com.guildup.discord.oauth.config.DiscordOAuthProperties;
import com.guildup.user.auth.service.DiscordLoginService;
import com.guildup.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import com.guildup.user.auth.service.AuthSessionService;
import com.guildup.user.auth.service.DiscordAuthAttempt;
import com.guildup.user.repository.UserRepository;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

class DiscordLoginControllerTests {

    private final DiscordOAuthProperties discordOAuthProperties =
            mock(DiscordOAuthProperties.class);

    private final DiscordLoginService discordLoginService =
            mock(DiscordLoginService.class);

    private final UserRepository users = mock(UserRepository.class);
    private final AuthSessionService sessions = new AuthSessionService(users);

    private final MockMvc mockMvc =
            MockMvcBuilders.standaloneSetup(
                    new DiscordLoginController(
                            discordOAuthProperties,
                            discordLoginService,
                            sessions
                    ), new AuthController(sessions,
                            mock(com.guildup.user.auth.service.CredentialAuthService.class),
                            mock(com.guildup.user.auth.service.AccountSettingsService.class))
            ).build();

    @Test
    void logsInUserAndStoresUserIdInSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String beforeLoginId = session.getId();
        session.setAttribute("PRE_LOGIN_CONTEXT", "keep-me");
        String beforeLoginToken = com.guildup.user.auth.service.SessionCsrfTokens.rotate(session);

        setAttempt(session, "http://localhost:8080/api/auth/discord/callback");

        DiscordApiUser discordUser = new DiscordApiUser(
                "123456789",
                "apple",
                "애플",
                "avatar-hash"
        );

        User user = mock(User.class);

        when(user.getId()).thenReturn(10L);

        when(discordLoginService.getDiscordUser(
                "authorization-code",
                "http://localhost:8080/api/auth/discord/callback"
        )).thenReturn(discordUser);

        when(discordLoginService.findOrCreateUser(discordUser))
                .thenReturn(user);

        mockMvc.perform(
                        get("/api/auth/discord/callback")
                                .param(
                                        "code",
                                        "authorization-code"
                                )
                                .param(
                                        "state",
                                        "valid-state"
                                )
                                .session(session)
                )
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/communities.html"));

        assertThat(session.getId()).isNotEqualTo(beforeLoginId);
        assertThat(session.getAttribute("PRE_LOGIN_CONTEXT")).isEqualTo("keep-me");
        assertThat(com.guildup.user.auth.service.SessionCsrfTokens.matches(session, beforeLoginToken)).isFalse();

        assertThat(
                session.getAttribute("LOGIN_USER_ID")
        ).isEqualTo(10L);

        assertThat(
                session.getAttribute(DiscordAuthAttempt.ATTRIBUTE)
        ).isNull();

        assertThat(
                session.getAttribute(
                        DiscordAuthAttempt.ATTRIBUTE
                )
        ).isNull();

        verify(discordLoginService).getDiscordUser(
                "authorization-code",
                "http://localhost:8080/api/auth/discord/callback"
        );

        verify(discordLoginService)
                .findOrCreateUser(discordUser);
    }

    @Test
    void redirectsToLoginWhenOAuthSessionStateIsMissing() throws Exception {
        mockMvc.perform(
                        get("/api/auth/discord/callback")
                                .param("code", "authorization-code")
                                .param("state", "expired-state")
                )
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login.html?oauthError=session"));
    }

    @Test
    void redirectsToLoginWhenDiscordAuthorizationIsDenied() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String beforeLoginId = session.getId();
        setAttempt(session, "http://localhost:8080/api/auth/discord/callback");

        mockMvc.perform(
                        get("/api/auth/discord/callback")
                                .param("error", "access_denied")
                                .param("state", "valid-state")
                                .session(session)
                )
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login.html?oauthError=discord"));

        assertThat(session.getAttribute(DiscordAuthAttempt.ATTRIBUTE)).isNull();
        assertThat(session.getAttribute(DiscordAuthAttempt.ATTRIBUTE)).isNull();
        assertThat(session.getId()).isEqualTo(beforeLoginId);
        assertThat(session.getAttribute("LOGIN_USER_ID")).isNull();
    }

    @Test
    void rejectsWrongStateWithoutPromotingSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String beforeLoginId = session.getId();
        setAttempt(session, "http://localhost/api/auth/discord/callback");
        mockMvc.perform(get("/api/auth/discord/callback").session(session)
                        .param("code", "code").param("state", "wrong-state"))
                .andExpect(redirectedUrl("/login.html?oauthError=session"));
        assertThat(session.getId()).isEqualTo(beforeLoginId);
        assertThat(session.getAttribute("LOGIN_USER_ID")).isNull();
        org.mockito.Mockito.verifyNoInteractions(discordLoginService);
    }

    @Test
    void discordFailureDoesNotPromoteSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String beforeLoginId = session.getId();
        setAttempt(session, "http://localhost/api/auth/discord/callback");
        when(discordLoginService.getDiscordUser("code", "http://localhost/api/auth/discord/callback"))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY, "Discord unavailable"));
        mockMvc.perform(get("/api/auth/discord/callback").session(session)
                        .param("code", "code").param("state", "valid-state"))
                .andExpect(status().isBadGateway());
        assertThat(session.getId()).isEqualTo(beforeLoginId);
        assertThat(session.getAttribute("LOGIN_USER_ID")).isNull();
        org.mockito.Mockito.verify(discordLoginService, org.mockito.Mockito.never())
                .findOrCreateUser(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void returnsCurrentLoginUser() throws Exception {
        MockHttpSession session = new MockHttpSession();

        session.setAttribute("LOGIN_USER_ID", 10L);

        User user = mock(User.class);

        when(user.getId()).thenReturn(10L);
        when(user.getNickname()).thenReturn("애플");

        when(users.findById(10L))
                .thenReturn(Optional.of(user));

        mockMvc.perform(
                        get("/api/auth/me")
                                .session(session)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.nickname").value("애플"))
                .andExpect(jsonPath("$.systemAdmin").value(false));

        verify(users).findById(10L);
    }

    @Test
    void returnsUnauthorizedWhenSessionHasNoLoginUser() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(
                        get("/api/auth/me")
                                .session(session)
                )
                .andExpect(status().isUnauthorized());
    }
    @Test
    void returnsUnauthorizedWhenLoginUserDoesNotExist() throws Exception {
        MockHttpSession session = new MockHttpSession();

        session.setAttribute("LOGIN_USER_ID", 999L);

        when(users.findById(999L))
                .thenReturn(Optional.empty());

        mockMvc.perform(
                        get("/api/auth/me")
                                .session(session)
                )
                .andExpect(status().isUnauthorized());

        verify(users).findById(999L);
    }

    @Test
    void invalidatesSessionWhenLoggingOut() throws Exception {
        MockHttpSession session = new MockHttpSession();

        session.setAttribute("LOGIN_USER_ID", 10L);

        mockMvc.perform(
                        post("/api/auth/logout")
                                .session(session)
                )
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void allowsLogoutWithoutExistingSession() throws Exception {
        mockMvc.perform(
                        post("/api/auth/logout")
                )
                .andExpect(status().isNoContent());
    }
    private void setAttempt(MockHttpSession session, String redirectUri) {
        session.setAttribute(DiscordAuthAttempt.ATTRIBUTE, new DiscordAuthAttempt("valid-state", redirectUri,
                DiscordAuthAttempt.Purpose.LOGIN, null, java.time.Instant.now()));
    }
}
