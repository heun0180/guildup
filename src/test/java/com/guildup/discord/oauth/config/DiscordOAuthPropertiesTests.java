package com.guildup.discord.oauth.config;

import com.guildup.discord.oauth.exception.DiscordOAuthConfigurationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscordOAuthPropertiesTests {

    private static final String SECRET = "must-never-appear-in-an-error";
    private static final String REDIRECT_URI = "http://localhost:8080/api/discord/oauth/callback";

    @Test
    void reportsMissingClientIdWithoutExposingSecret() {
        DiscordOAuthProperties properties = new DiscordOAuthProperties("", SECRET, REDIRECT_URI);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(DiscordOAuthConfigurationException.class)
                .hasMessageContaining("DISCORD_CLIENT_ID")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void reportsMissingClientSecret() {
        DiscordOAuthProperties properties = new DiscordOAuthProperties("client-id", "", REDIRECT_URI);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(DiscordOAuthConfigurationException.class)
                .hasMessageContaining("DISCORD_CLIENT_SECRET")
                .hasMessageNotContaining("client-id");
    }

    @Test
    void reportsMissingRedirectUriWithoutExposingSecret() {
        DiscordOAuthProperties properties = new DiscordOAuthProperties("client-id", SECRET, " ");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(DiscordOAuthConfigurationException.class)
                .hasMessageContaining("DISCORD_REDIRECT_URI")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void acceptsCompleteOAuthConfiguration() {
        DiscordOAuthProperties properties =
                new DiscordOAuthProperties("client-id", SECRET, REDIRECT_URI);

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}
