package com.guildup.discord.oauth.config;

import com.guildup.discord.oauth.exception.DiscordOAuthConfigurationException;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code discord.oauth.*} 설정을 타입 안전하게 읽는다.
 * 실제 값은 application.properties를 통해 DISCORD_CLIENT_ID, DISCORD_CLIENT_SECRET,
 * DISCORD_REDIRECT_URI 환경변수에서 주입된다.
 */
@ConfigurationProperties(prefix = "discord.oauth")
public record DiscordOAuthProperties(
        String clientId,
        String clientSecret,
        String redirectUri
) {

    /** 사용자 OAuth 전체 흐름에 필요한 세 가지 설정이 모두 존재하는지 확인한다. */
    public void validate() {
        requireConfigured(clientId, "DISCORD_CLIENT_ID");
        requireConfigured(clientSecret, "DISCORD_CLIENT_SECRET");
        requireConfigured(redirectUri, "DISCORD_REDIRECT_URI");
    }

    /** 봇 초대 URL 생성에는 client ID만 필요하므로 해당 값만 확인한다. */
    public void validateBotInstall() {
        requireConfigured(clientId, "DISCORD_CLIENT_ID");
    }

    /** 필수 설정이 비어 있으면 어떤 환경변수를 확인해야 하는지 포함해 예외를 발생시킨다. */
    private void requireConfigured(String value, String environmentVariable) {
        if (value == null || value.isBlank()) {
            throw new DiscordOAuthConfigurationException(environmentVariable);
        }
    }
}
