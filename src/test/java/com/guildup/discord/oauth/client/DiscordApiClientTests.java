package com.guildup.discord.oauth.client;

import com.guildup.discord.oauth.client.dto.DiscordAccessTokenResponse;
import com.guildup.discord.oauth.config.DiscordOAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.guildup.monitoring.logging.FailureLogContext;

class DiscordApiClientTests {

    @Test
    void failedOAuthCallHasEndpointTraceAndSingleErrorOwnership() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DiscordApiClient client = new DiscordApiClient(builder.build(), new DiscordOAuthProperties(
                "client-id", "secret", "http://localhost/callback"));
        server.expect(requestTo("https://discord.com/api/oauth2/token"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DiscordApiClient.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> client.exchangeCode("code"))
                    .isInstanceOf(org.springframework.web.client.RestClientResponseException.class)
                    .satisfies(failure -> assertThat(FailureLogContext.isLogged(failure)).isTrue());
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getLevel()).isEqualTo(ch.qos.logback.classic.Level.ERROR);
            assertThat(appender.list.getFirst().getThrowableProxy()).isNotNull();
            assertThat(appender.list.getFirst().getFormattedMessage()).contains("OAUTH_TOKEN", "POST", "502", "elapsedMs=")
                    .doesNotContain("client-secret");
            server.verify();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void tokenStringRepresentationIsRedacted() {
        var response = new DiscordAccessTokenResponse("never-log-this-token", "Bearer", 1, "identify");
        assertThat(response.toString()).contains("[REDACTED]").doesNotContain("never-log-this-token");
    }

    @Test
    void mapsDiscordAccessTokenResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DiscordApiClient client = new DiscordApiClient(
                builder.build(),
                new DiscordOAuthProperties(
                        "client-id",
                        "client-secret",
                        "http://localhost:8080/api/discord/oauth/callback"
                )
        );
        server.expect(requestTo("https://discord.com/api/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of(
                        "client_id", "client-id",
                        "client_secret", "client-secret",
                        "grant_type", "authorization_code",
                        "code", "authorization-code",
                        "redirect_uri", "http://localhost:8080/api/discord/oauth/callback"
                )))
                .andRespond(withSuccess("""
                        {
                          "access_token": "access-token",
                          "token_type": "Bearer",
                          "expires_in": 604800,
                          "scope": "identify guilds"
                        }
                        """, MediaType.APPLICATION_JSON));

        DiscordAccessTokenResponse response = client.exchangeCode("authorization-code");

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(604800);
        server.verify();
    }

    @Test
    void usesProvidedLoginRedirectUriWhenExchangingCode() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();

        DiscordApiClient client = new DiscordApiClient(
                builder.build(),
                new DiscordOAuthProperties(
                        "client-id",
                        "client-secret",
                        "http://localhost:8080/api/discord/oauth/callback"
                )
        );

        server.expect(requestTo("https://discord.com/api/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(
                        MediaType.APPLICATION_FORM_URLENCODED
                ))
                .andExpect(content().formDataContains(Map.of(
                        "client_id", "client-id",
                        "client_secret", "client-secret",
                        "grant_type", "authorization_code",
                        "code", "login-authorization-code",
                        "redirect_uri",
                        "http://localhost:8080/api/auth/discord/callback"
                )))
                .andRespond(withSuccess("""
                    {
                      "access_token": "login-access-token",
                      "token_type": "Bearer",
                      "expires_in": 3600,
                      "scope": "identify"
                    }
                    """, MediaType.APPLICATION_JSON));

        DiscordAccessTokenResponse response = client.exchangeCode(
                "login-authorization-code",
                "http://localhost:8080/api/auth/discord/callback"
        );

        assertThat(response.accessToken())
                .isEqualTo("login-access-token");
        assertThat(response.scope())
                .isEqualTo("identify");

        server.verify();
    }
}
