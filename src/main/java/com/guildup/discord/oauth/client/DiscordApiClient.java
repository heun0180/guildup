package com.guildup.discord.oauth.client;

import com.guildup.discord.oauth.client.dto.DiscordAccessTokenResponse;
import com.guildup.discord.oauth.client.dto.DiscordApiGuild;
import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import com.guildup.discord.oauth.config.DiscordOAuthProperties;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.monitoring.logging.FailureLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Discord OAuth 및 사용자 API를 실제 HTTP 요청으로 호출하는 클라이언트다.
 * 서비스 계층은 Discord의 URL이나 요청 형식을 직접 알지 않고 이 클래스를 통해 통신한다.
 */
@Component
public class DiscordApiClient {
    private static final Logger log = LoggerFactory.getLogger(DiscordApiClient.class);

    // 사용자 정보와 참여 서버 목록을 조회할 때 사용하는 Discord REST API 주소다.
    private static final String DISCORD_API_URL = "https://discord.com/api/v10";
    // OAuth 인증 코드를 액세스 토큰으로 교환하는 주소다.
    private static final String DISCORD_TOKEN_URL = "https://discord.com/api/oauth2/token";

    private final RestClient restClient;
    private final DiscordOAuthProperties properties;
    private final MonitoringEventService monitoring;

    public DiscordApiClient(RestClient discordOAuthRestClient, DiscordOAuthProperties properties) {
        this(discordOAuthRestClient, properties, null);
    }

    @Autowired
    public DiscordApiClient(RestClient discordOAuthRestClient, DiscordOAuthProperties properties,
                            MonitoringEventService monitoring) {
        this.restClient = discordOAuthRestClient;
        this.properties = properties;
        this.monitoring = monitoring;
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

        DiscordAccessTokenResponse response = call("OAUTH_TOKEN", "POST", () -> restClient.post()
                .uri(DISCORD_TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(DiscordAccessTokenResponse.class));

        if (response == null
                || response.accessToken() == null
                || response.accessToken().isBlank()) {
            recordFailure("OAUTH_TOKEN", null, "EMPTY_RESPONSE");
            throw emptyResponse("OAUTH_TOKEN");
        }

        return response;
    }

    /** 액세스 토큰 주인인 현재 Discord 사용자의 프로필을 조회한다. */
    public DiscordApiUser getCurrentUser(String accessToken) {
        DiscordApiUser user = call("CURRENT_USER", "GET", () -> restClient.get()
                .uri(DISCORD_API_URL + "/users/@me")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(DiscordApiUser.class));

        if (user == null) {
            recordFailure("CURRENT_USER", null, "EMPTY_RESPONSE");
            throw emptyResponse("CURRENT_USER");
        }

        return user;
    }

    /** 현재 Discord 사용자가 참여하고 있는 서버 목록과 각 서버에서의 권한을 조회한다. */
    public List<DiscordApiGuild> getCurrentUserGuilds(String accessToken) {
        List<DiscordApiGuild> guilds = call("CURRENT_USER_GUILDS", "GET", () -> restClient.get()
                .uri(DISCORD_API_URL + "/users/@me/guilds")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                }));

        // Discord가 빈 본문을 반환해도 호출자가 null 검사를 반복하지 않도록 빈 목록으로 바꾼다.
        if (guilds == null) {
            log.warn("Discord API returned no guild-list body; empty-list fallback used. endpoint=CURRENT_USER_GUILDS, method=GET");
        }
        return guilds == null ? List.of() : guilds;
    }

    private IllegalStateException emptyResponse(String endpoint) {
        IllegalStateException failure = new IllegalStateException("Discord API response is empty: " + endpoint);
        log.error("Discord API response validation failed. endpoint={}, failureType=EMPTY_RESPONSE", endpoint, failure);
        FailureLogContext.markLogged(failure);
        return failure;
    }

    private <T> T call(String endpoint, String method, Supplier<T> request) {
        long started = System.nanoTime();
        try {
            return request.get();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            boolean clientRejection = status >= 400 && status < 500;
            (clientRejection ? log.atWarn() : log.atError()).setCause(exception).log(
                    "Discord API request failed. endpoint={}, method={}, status={}, elapsedMs={}",
                    endpoint, method, status, (System.nanoTime() - started) / 1_000_000);
            if (!clientRejection) FailureLogContext.markLogged(exception);
            MonitoringEventCode code = status == 429
                    ? MonitoringEventCode.DISCORD_RATE_LIMIT : MonitoringEventCode.DISCORD_API_FAILED;
            recordFailure(endpoint, status, exception.getClass().getSimpleName(), code);
            throw exception;
        } catch (RestClientException exception) {
            log.error("Discord API transport or parsing failed. endpoint={}, method={}, failureType={}, elapsedMs={}",
                    endpoint, method, exception.getClass().getSimpleName(),
                    (System.nanoTime() - started) / 1_000_000, exception);
            FailureLogContext.markLogged(exception);
            recordFailure(endpoint, null, exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private void recordFailure(String endpoint, Integer status, String failureType) {
        recordFailure(endpoint, status, failureType, MonitoringEventCode.DISCORD_API_FAILED);
    }

    private void recordFailure(String endpoint, Integer status, String failureType, MonitoringEventCode code) {
        if (monitoring == null) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("endpoint", endpoint);
        if (status != null) metadata.put("status", status);
        metadata.put("failureType", failureType);
        if (status != null && status == 429) {
            monitoring.recordWarn(MonitoringCategory.DISCORD, code, "Discord API rate limit reached",
                    null, null, endpoint, metadata);
        } else if (status != null && status >= 400 && status < 500) {
            monitoring.recordWarn(MonitoringCategory.DISCORD, code, "Discord API rejected the request",
                    null, null, endpoint, metadata);
        } else {
            monitoring.recordError(MonitoringCategory.DISCORD, code, "Discord API request failed",
                    null, null, endpoint, metadata);
        }
    }
}
