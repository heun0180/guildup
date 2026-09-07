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

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

class DiscordLoginControllerTests {

    private final DiscordOAuthProperties discordOAuthProperties =
            mock(DiscordOAuthProperties.class);

    private final DiscordLoginService discordLoginService =
            mock(DiscordLoginService.class);

    private final MockMvc mockMvc =
            MockMvcBuilders.standaloneSetup(
                    new DiscordLoginController(
                            discordOAuthProperties,
                            discordLoginService
                    )
            ).build();

    @Test
    void logsInUserAndStoresUserIdInSession() throws Exception {
        MockHttpSession session = new MockHttpSession();

        session.setAttribute(
                "DISCORD_LOGIN_STATE",
                "valid-state"
        );

        session.setAttribute(
                "DISCORD_LOGIN_REDIRECT_URI",
                "http://localhost:8080/api/auth/discord/callback"
        );

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

        assertThat(
                session.getAttribute("LOGIN_USER_ID")
        ).isEqualTo(10L);

        assertThat(
                session.getAttribute("DISCORD_LOGIN_STATE")
        ).isNull();

        assertThat(
                session.getAttribute(
                        "DISCORD_LOGIN_REDIRECT_URI"
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
    void returnsCurrentLoginUser() throws Exception {
        MockHttpSession session = new MockHttpSession();

        session.setAttribute("LOGIN_USER_ID", 10L);

        User user = mock(User.class);

        when(user.getId()).thenReturn(10L);
        when(user.getNickname()).thenReturn("애플");

        when(discordLoginService.findUserById(10L))
                .thenReturn(Optional.of(user));

        mockMvc.perform(
                        get("/api/auth/me")
                                .session(session)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.nickname").value("애플"));

        verify(discordLoginService).findUserById(10L);
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

        when(discordLoginService.findUserById(999L))
                .thenReturn(Optional.empty());

        mockMvc.perform(
                        get("/api/auth/me")
                                .session(session)
                )
                .andExpect(status().isUnauthorized());

        verify(discordLoginService).findUserById(999L);
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
}