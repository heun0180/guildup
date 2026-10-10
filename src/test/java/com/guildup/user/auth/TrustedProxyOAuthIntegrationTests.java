package com.guildup.user.auth;

import com.guildup.discord.bot.DiscordBot;
import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import com.guildup.user.auth.service.DiscordLoginService;
import com.guildup.user.domain.User;
import com.guildup.user.repository.UserRepository;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:trusted-proxy-oauth;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "discord.oauth.client-id=test-client", "discord.oauth.client-secret=test-secret",
        "discord.oauth.redirect-uri=http://localhost/api/discord/oauth/callback",
        "security.proxy.trusted-proxies=127.0.0.1/32,::1/128"
})
class TrustedProxyOAuthIntegrationTests {
    @Value("${local.server.port}") int port;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    @MockitoBean DiscordLoginService login;
    @org.springframework.beans.factory.annotation.Autowired UserRepository users;
    final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test void realTrustedNginxHeadersKeepHttpsRedirectAndIgnoreForwardedHostPoisoning() throws Exception {
        var response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/discord/authorize"))
                        .header("X-Forwarded-Proto", "https").header("X-Forwarded-Port", "443")
                        .header("X-Forwarded-For", "203.0.113.1")
                        .header("Forwarded", "for=198.51.100.1;host=evil.test;proto=http")
                        .header("X-Forwarded-Host", "evil.test").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);
        var params = UriComponentsBuilder.fromUriString(response.headers().firstValue("Location").orElseThrow()).build().getQueryParams();
        assertThat(java.net.URLDecoder.decode(params.getFirst("redirect_uri"), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("https://127.0.0.1/api/auth/discord/callback");
        assertThat(params.getFirst("state")).isNotBlank();
    }

    @Test void trustedHttpsProxyCompletesOAuthRotatesSessionAndAllowsReloginAfterLogout() throws Exception {
        var discordUser = new DiscordApiUser("proxy-discord-id", "proxy-user", "proxy-user", null);
        User user = users.saveAndFlush(new User("proxy-user"));
        when(login.getDiscordUser("proxy-code", "https://127.0.0.1/api/auth/discord/callback")).thenReturn(discordUser);
        when(login.findOrCreateUser(discordUser)).thenReturn(user);
        for (int attempt = 0; attempt < 2; attempt++) {
            var authorize = send(proxyRequest("/api/auth/discord/authorize", null).GET());
            assertThat(authorize.statusCode()).isEqualTo(302);
            String anonymousCookie = cookie(authorize);
            String state = UriComponentsBuilder.fromUriString(authorize.headers().firstValue("Location").orElseThrow())
                    .build().getQueryParams().getFirst("state");
            var callback = send(proxyRequest("/api/auth/discord/callback?code=proxy-code&state=" + state, anonymousCookie).GET());
            assertThat(callback.statusCode()).isEqualTo(302);
            assertThat(callback.headers().firstValue("Location")).contains("/communities.html");
            String authenticatedCookie = cookie(callback);
            assertThat(authenticatedCookie).isNotEqualTo(anonymousCookie);
            var me = send(proxyRequest("/api/auth/me", authenticatedCookie).GET());
            assertThat(me.statusCode()).isEqualTo(200);
            assertThat(new tools.jackson.databind.ObjectMapper().readTree(me.body()).get("id").asLong()).isEqualTo(user.getId());
            assertThat(send(proxyRequest("/api/auth/me", anonymousCookie).GET()).statusCode()).isEqualTo(401);
            var csrf = send(proxyRequest("/api/auth/csrf", authenticatedCookie).GET());
            String token = new tools.jackson.databind.ObjectMapper().readTree(csrf.body()).get("token").asText();
            assertThat(send(proxyRequest("/api/auth/logout", authenticatedCookie).header("X-CSRF-Token", token)
                    .POST(HttpRequest.BodyPublishers.noBody())).statusCode()).isEqualTo(204);
            assertThat(send(proxyRequest("/api/auth/me", authenticatedCookie).GET()).statusCode()).isEqualTo(401);
            // The single-use state cannot be replayed after logout.
            var replay = send(proxyRequest("/api/auth/discord/callback?code=proxy-code&state=" + state, authenticatedCookie).GET());
            assertThat(replay.statusCode()).isEqualTo(302);
            assertThat(replay.headers().firstValue("Location")).contains("/login.html?oauthError=session");
        }
        verify(login, times(2)).getDiscordUser("proxy-code", "https://127.0.0.1/api/auth/discord/callback");
    }

    private HttpRequest.Builder proxyRequest(String path, String cookie) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Forwarded-Proto", "https").header("X-Forwarded-Port", "443")
                .header("X-Forwarded-For", "203.0.113.1");
        if (cookie != null) request.header("Cookie", cookie);
        return request;
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String cookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("JSESSIONID="))
                .map(value -> value.split(";", 2)[0]).findFirst().orElseThrow();
    }
}
