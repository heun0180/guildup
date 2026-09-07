package com.guildup.discord.oauth.client;

import com.guildup.discord.oauth.client.dto.DiscordAccessTokenResponse;
import com.guildup.discord.oauth.client.dto.DiscordApiGuild;
import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import com.guildup.discord.oauth.config.DiscordOAuthProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Discord OAuth 및 사용자 API를 실제 HTTP 요청으로 호출하는 클라이언트다.
 * 서비스 계층은 Discord의 URL이나 요청 형식을 직접 알지 않고 이 클래스를 통해 통신한다.
 */
@Component
public class DiscordApiClient {

    // 사용자 정보와 참여 서버 목록을 조회할 때 사용하는 Discord REST API 주소다.
    private static final String DISCORD_API_URL = "https://discord.com/api/v10";
    // OAuth 인증 코드를 액세스 토큰으로 교환하는 주소다.
    private static final String DISCORD_TOKEN_URL = "https://discord.com/api/oauth2/token";

    private final RestClient restClient;
    private final DiscordOAuthProperties properties;

    public DiscordApiClient(RestClient discordOAuthRestClient, DiscordOAuthProperties properties) {
        this.restClient = discordOAuthRestClient;
        this.properties = properties;
    }

    /**
     * Community Discord 연결 OAuth에서 사용하는 기존 토큰 교환 메서드다.
     * 기존 Community callback 주소를 그대로 사용한다.
     */
    public DiscordAccessTokenResponse exchangeCode(String code) {
        return exchangeCode(code, properties.redirectUri());
    }

    /**
     * 호출자가 전달한 redirect URI를 사용해
     * Discord 인증 코드를 access token으로 교환한다.
     */
    public DiscordAccessTokenResponse exchangeCode(
            String code,
            String redirectUri
    ) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);

        DiscordAccessTokenResponse response = restClient.post()
                .uri(DISCORD_TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(DiscordAccessTokenResponse.class);

        if (response == null
                || response.accessToken() == null
                || response.accessToken().isBlank()) {
            throw new IllegalStateException(
                    "Discord access token response is empty"
            );
        }

        return response;
    }

    /** 액세스 토큰 주인인 현재 Discord 사용자의 프로필을 조회한다. */
    public DiscordApiUser getCurrentUser(String accessToken) {
        DiscordApiUser user = restClient.get()
                .uri(DISCORD_API_URL + "/users/@me")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(DiscordApiUser.class);

        if (user == null) {
            throw new IllegalStateException("Discord user response is empty");
        }

        return user;
    }

    /** 현재 Discord 사용자가 참여하고 있는 서버 목록과 각 서버에서의 권한을 조회한다. */
    public List<DiscordApiGuild> getCurrentUserGuilds(String accessToken) {
        List<DiscordApiGuild> guilds = restClient.get()
                .uri(DISCORD_API_URL + "/users/@me/guilds")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        // Discord가 빈 본문을 반환해도 호출자가 null 검사를 반복하지 않도록 빈 목록으로 바꾼다.
        return guilds == null ? List.of() : guilds;
    }
}
