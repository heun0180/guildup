package com.guildup.user.auth;

import com.guildup.discord.bot.DiscordBot;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.util.UriComponentsBuilder;
import java.net.URI;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;

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
}
