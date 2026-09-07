package com.guildup.discord.oauth.service;

import com.guildup.community.repository.CommunityRepository;
import com.guildup.discord.oauth.client.DiscordApiClient;
import com.guildup.discord.oauth.client.dto.DiscordAccessTokenResponse;
import com.guildup.discord.oauth.client.dto.DiscordApiGuild;
import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import com.guildup.discord.oauth.config.DiscordOAuthProperties;
import com.guildup.discord.oauth.dto.DiscordOAuthResultResponse;
import com.guildup.discord.oauth.store.DiscordOAuthSessionStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordOAuthServiceTests {

    private final CommunityRepository communityRepository = mock(CommunityRepository.class);
    private final DiscordOAuthSessionStore sessionStore = mock(DiscordOAuthSessionStore.class);
    private final DiscordApiClient discordApiClient = mock(DiscordApiClient.class);
    private final DiscordOAuthProperties properties = new DiscordOAuthProperties(
            "client-id",
            "client-secret",
            "http://localhost:8080/api/discord/oauth/callback"
    );
    private final DiscordOAuthService oauthService = new DiscordOAuthService(
            communityRepository,
            properties,
            sessionStore,
            discordApiClient
    );

    @Test
    void createsAuthorizationUrlWithRequiredScopesAndOpaqueState() {
        when(communityRepository.existsById(1L)).thenReturn(true);
        when(sessionStore.createState(1L)).thenReturn("random-state");

        String authorizationUrl = oauthService.createAuthorizationUrl(1L);
        UriComponents uri = UriComponentsBuilder.fromUriString(authorizationUrl).build();

        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("discord.com");
        assertThat(uri.getQueryParams().getFirst("response_type")).isEqualTo("code");
        assertThat(uri.getQueryParams().getFirst("client_id")).isEqualTo("client-id");
        assertThat(decodedQueryParam(uri, "scope")).isEqualTo("identify guilds");
        assertThat(uri.getQueryParams().getFirst("state")).isEqualTo("random-state");
        assertThat(decodedQueryParam(uri, "redirect_uri"))
                .isEqualTo("http://localhost:8080/api/discord/oauth/callback");
    }

    @Test
    void exchangesTokenAndKeepsOnlyManageableGuilds() {
        DiscordAccessTokenResponse token =
                new DiscordAccessTokenResponse("user-token", "Bearer", 3600, "identify guilds");
        DiscordApiUser user = new DiscordApiUser("10", "apple", "애플", "avatar-hash");
        List<DiscordApiGuild> guilds = List.of(
                new DiscordApiGuild("1", "Owner", null, true, "0"),
                new DiscordApiGuild("2", "Administrator", null, false, "8"),
                new DiscordApiGuild("3", "Manage Guild", null, false, "32"),
                new DiscordApiGuild("4", "Member", null, false, "1024")
        );
        when(sessionStore.consumeState("valid-state")).thenReturn(7L);
        when(discordApiClient.exchangeCode("authorization-code")).thenReturn(token);
        when(discordApiClient.getCurrentUser("user-token")).thenReturn(user);
        when(discordApiClient.getCurrentUserGuilds("user-token")).thenReturn(guilds);
        when(sessionStore.saveResult(eq(7L), any())).thenReturn("result-id");

        DiscordOAuthService.OAuthCompletion completion =
                oauthService.completeAuthorization("authorization-code", "valid-state");

        assertThat(completion).isEqualTo(new DiscordOAuthService.OAuthCompletion(7L, "result-id"));
        ArgumentCaptor<DiscordOAuthResultResponse> resultCaptor =
                ArgumentCaptor.forClass(DiscordOAuthResultResponse.class);
        verify(sessionStore).saveResult(eq(7L), resultCaptor.capture());
        DiscordOAuthResultResponse result = resultCaptor.getValue();
        assertThat(result.user().discordUserId()).isEqualTo("10");
        assertThat(result.guilds())
                .extracting(guild -> guild.name())
                .containsExactly("Administrator", "Manage Guild", "Owner");
        verify(discordApiClient).getCurrentUser("user-token");
        verify(discordApiClient).getCurrentUserGuilds("user-token");
    }

    private String decodedQueryParam(UriComponents uri, String name) {
        return UriUtils.decode(uri.getQueryParams().getFirst(name), StandardCharsets.UTF_8);
    }
}
