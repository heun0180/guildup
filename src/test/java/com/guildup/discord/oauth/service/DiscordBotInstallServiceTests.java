package com.guildup.discord.oauth.service;

import com.guildup.community.service.DiscordCommunityConnectionService;
import com.guildup.discord.oauth.config.DiscordOAuthProperties;
import com.guildup.discord.oauth.dto.DiscordBotInstallConfirmResponse;
import com.guildup.discord.oauth.dto.DiscordBotInstallStartResponse;
import com.guildup.discord.oauth.dto.DiscordManageableGuildResponse;
import com.guildup.discord.oauth.exception.DiscordBotNotInstalledException;
import com.guildup.discord.oauth.exception.InvalidDiscordGuildSelectionException;
import com.guildup.discord.oauth.store.DiscordBotInstallSession;
import com.guildup.discord.oauth.store.DiscordBotInstallStore;
import com.guildup.discord.oauth.store.DiscordOAuthSessionStore;
import com.guildup.discord.service.DiscordGuildService;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordBotInstallServiceTests {

    private final DiscordOAuthSessionStore oauthSessionStore = mock(DiscordOAuthSessionStore.class);
    private final DiscordBotInstallStore botInstallStore = mock(DiscordBotInstallStore.class);
    private final DiscordGuildService discordGuildService = mock(DiscordGuildService.class);
    private final DiscordCommunityConnectionService connectionService =
            mock(DiscordCommunityConnectionService.class);
    private final DiscordOAuthProperties properties = new DiscordOAuthProperties(
            "client-id",
            "client-secret",
            "http://localhost:8080/api/discord/oauth/callback"
    );
    private final DiscordBotInstallService installService = new DiscordBotInstallService(
            properties,
            oauthSessionStore,
            botInstallStore,
            discordGuildService,
            connectionService,
            1,
            0
    );

    @Test
    void createsCallbackFreeBotInstallUrlAndTokenForVerifiedGuild() {
        DiscordManageableGuildResponse guild =
                new DiscordManageableGuildResponse("100", "치즈 클랜", null, true);
        DiscordBotInstallSession session = new DiscordBotInstallSession(1L, "100", "치즈 클랜");
        when(oauthSessionStore.consumeSelectedGuild(1L, "oauth-result", "100")).thenReturn(guild);
        when(discordGuildService.findGuildById("100")).thenReturn(Optional.empty());
        when(botInstallStore.createInstallToken(session)).thenReturn("install-token");

        DiscordBotInstallStartResponse result =
                installService.startInstallation(1L, "oauth-result", "100");
        UriComponents uri = UriComponentsBuilder.fromUriString(result.authorizationUrl()).build();

        assertThat(result.alreadyInstalled()).isFalse();
        assertThat(result.installToken()).isEqualTo("install-token");
        assertThat(uri.getQueryParams().getFirst("client_id")).isEqualTo("client-id");
        assertThat(decodedQueryParam(uri, "scope")).isEqualTo("bot");
        assertThat(uri.getQueryParams().getFirst("permissions")).isEqualTo("0");
        assertThat(uri.getQueryParams().getFirst("guild_id")).isEqualTo("100");
        assertThat(uri.getQueryParams().getFirst("disable_guild_select")).isEqualTo("true");
        assertThat(uri.getQueryParams()).doesNotContainKeys("response_type", "redirect_uri", "state");
        verify(connectionService, never()).connect(1L, "100", "치즈 클랜");
    }

    @Test
    void cannotCreateInstallTokenForGuildOutsideOAuthResult() {
        when(oauthSessionStore.consumeSelectedGuild(1L, "oauth-result", "999"))
                .thenThrow(new InvalidDiscordGuildSelectionException());

        assertThatThrownBy(() -> installService.startInstallation(1L, "oauth-result", "999"))
                .isInstanceOf(InvalidDiscordGuildSelectionException.class);
        verify(botInstallStore, never()).createInstallToken(
                new DiscordBotInstallSession(1L, "999", "조작")
        );
        verify(connectionService, never()).connect(1L, "999", "조작");
    }

    @Test
    void connectsAndRemovesTokenAfterJdaConfirmsGuild() {
        DiscordBotInstallSession session = new DiscordBotInstallSession(1L, "100", "치즈 클랜");
        Guild guild = guild("100", "치즈 클랜");
        when(botInstallStore.getInstallSession("install-token")).thenReturn(session);
        when(discordGuildService.findGuildById("100")).thenReturn(Optional.of(guild));

        DiscordBotInstallConfirmResponse result = installService.confirmInstallation("install-token");

        assertThat(result.connected()).isTrue();
        assertThat(result.communityId()).isEqualTo(1L);
        assertThat(result.guildName()).isEqualTo("치즈 클랜");
        verify(connectionService).connect(1L, "100", "치즈 클랜");
        verify(botInstallStore).removeInstallToken("install-token");
    }

    @Test
    void failedConfirmationDoesNotConnectOrRemoveTokenAndCanRetry() {
        DiscordBotInstallSession session = new DiscordBotInstallSession(1L, "100", "치즈 클랜");
        Guild guild = guild("100", "치즈 클랜");
        when(botInstallStore.getInstallSession("install-token")).thenReturn(session);
        when(discordGuildService.findGuildById("100"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(guild));

        assertThatThrownBy(() -> installService.confirmInstallation("install-token"))
                .isInstanceOf(DiscordBotNotInstalledException.class);
        verify(connectionService, never()).connect(1L, "100", "치즈 클랜");
        verify(botInstallStore, never()).removeInstallToken("install-token");

        DiscordBotInstallConfirmResponse retry = installService.confirmInstallation("install-token");

        assertThat(retry.connected()).isTrue();
        verify(connectionService).connect(1L, "100", "치즈 클랜");
        verify(botInstallStore).removeInstallToken("install-token");
        verify(botInstallStore, times(2)).getInstallSession("install-token");
    }

    @Test
    void connectsImmediatelyWhenBotIsAlreadyInstalled() {
        DiscordManageableGuildResponse selectedGuild =
                new DiscordManageableGuildResponse("100", "치즈 클랜", null, true);
        Guild guild = guild("100", "치즈 클랜");
        when(oauthSessionStore.consumeSelectedGuild(1L, "oauth-result", "100"))
                .thenReturn(selectedGuild);
        when(discordGuildService.findGuildById("100")).thenReturn(Optional.of(guild));

        DiscordBotInstallStartResponse result =
                installService.startInstallation(1L, "oauth-result", "100");

        assertThat(result.alreadyInstalled()).isTrue();
        assertThat(result.authorizationUrl()).isNull();
        assertThat(result.installToken()).isNull();
        assertThat(result.communityId()).isEqualTo(1L);
        assertThat(result.guildName()).isEqualTo("치즈 클랜");
        verify(connectionService).connect(1L, "100", "치즈 클랜");
        verify(botInstallStore, never()).createInstallToken(
                new DiscordBotInstallSession(1L, "100", "치즈 클랜")
        );
    }

    private Guild guild(String id, String name) {
        Guild guild = mock(Guild.class);
        when(guild.getId()).thenReturn(id);
        when(guild.getName()).thenReturn(name);
        return guild;
    }

    private String decodedQueryParam(UriComponents uri, String name) {
        return UriUtils.decode(uri.getQueryParams().getFirst(name), StandardCharsets.UTF_8);
    }
}
