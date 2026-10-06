package com.guildup.user.auth;

import com.guildup.discord.bot.DiscordBot;
import com.guildup.discord.oauth.client.dto.DiscordApiUser;
import com.guildup.user.auth.service.DiscordLoginService;
import com.guildup.user.domain.User;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real servlet-container cookies: MockMvc alone cannot resolve an old session ID. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:session-cookie;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "discord.oauth.client-id=test-client", "discord.oauth.client-secret=test-secret",
        "discord.oauth.redirect-uri=http://localhost/api/discord/oauth/callback"
})
class SessionCookieIntegrationTests {
    @Value("${local.server.port}") int port;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;
    @MockitoBean DiscordLoginService login;
    final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void rotatesCookieAndRejectsOldCookieAndLoggedOutCookie() throws Exception {
        var discordUser = new DiscordApiUser("discord-id", "test", "test", null);
        User user = mock(User.class);
        when(user.getId()).thenReturn(10L);
        when(user.getNickname()).thenReturn("test");
        when(login.getDiscordUser("code", "http://localhost:" + port + "/api/auth/discord/callback"))
                .thenReturn(discordUser);
        when(login.findOrCreateUser(discordUser)).thenReturn(user);
        when(login.findUserById(10L)).thenReturn(Optional.of(user));

        var authorize = get("/api/auth/discord/authorize", null);
        assertThat(authorize.statusCode()).isEqualTo(302);
        String oldCookie = sessionCookie(authorize);
        String state = UriComponentsBuilder.fromUriString(authorize.headers().firstValue("Location").orElseThrow())
                .build().getQueryParams().getFirst("state");
        assertThat(get("/api/auth/me", oldCookie).statusCode()).isEqualTo(401);
        var callback = get("/api/auth/discord/callback?code=code&state=" + state, oldCookie);
        assertThat(callback.statusCode()).isEqualTo(302);
        String newCookie = sessionCookie(callback);
        assertThat(newCookie).isNotEqualTo(oldCookie);
        String setCookie = callback.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(setCookie).contains("HttpOnly", "Path=/", "SameSite=Lax");
        if (secureCookieExpected()) assertThat(setCookie).contains("Secure");
        else assertThat(setCookie).doesNotContain("Secure");
        assertThat(get("/api/auth/me", newCookie).statusCode()).isEqualTo(200);
        assertThat(get("/api/auth/me", oldCookie).statusCode()).isEqualTo(401);
        // URL session tracking must not authenticate, even using the new ID.
        String id = newCookie.substring(newCookie.indexOf('=') + 1);
        assertThat(get("/api/auth/me;jsessionid=" + id, null).statusCode()).isEqualTo(401);

        var csrf = get("/api/auth/csrf", newCookie);
        assertThat(csrf.statusCode()).isEqualTo(200);
        String token = new tools.jackson.databind.ObjectMapper().readTree(csrf.body()).get("token").asText();
        var logout = client.send(HttpRequest.newBuilder(uri("/api/auth/logout"))
                .header("Cookie", newCookie).header("X-CSRF-Token", token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(get("/api/auth/me", newCookie).statusCode()).isEqualTo(401);
        assertThat(get("/api/auth/me", oldCookie).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).GET();
        if (cookie != null) request.header("Cookie", cookie);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://localhost:" + port + path); }

    protected boolean secureCookieExpected() { return false; }

    private String sessionCookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("JSESSIONID=")).map(value -> value.split(";", 2)[0])
                .findFirst().orElseThrow(() -> new AssertionError("Session cookie was not issued"));
    }
}
