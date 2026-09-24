package com.guildup.bingo;

import com.guildup.discord.bot.DiscordBot;
import com.guildup.user.auth.service.CurrentUserSession;
import jakarta.servlet.http.HttpSession;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 운영 dump를 복원한 로컬 DB에서만 명시적으로 실행하는 관찰용 baseline 하네스. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF"
})
@Import(BingoProductionDumpBaselineTests.SessionConfiguration.class)
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = ".+")
class BingoProductionDumpBaselineTests {
    private static final Logger log = LoggerFactory.getLogger(BingoProductionDumpBaselineTests.class);
    private static final long USER_ID = 1L;
    private static final long COMMUNITY_ID = 1L;
    private static final long GAME_ID = 1L;
    private static final long BINGO_ID = 36L;

    @LocalServerPort int port;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot discordBot;

    @Test
    void reproduceAggregationContentionAgainstProductionDump() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10)).build();
        call(client, "SESSION", "POST", "/__baseline/session");

        Map<String, String> endpoints = new LinkedHashMap<>();
        endpoints.put("LOGIN_ME", "/api/auth/me");
        endpoints.put("COMMUNITY", "/api/communities/" + COMMUNITY_ID);
        endpoints.put("BINGO_LIST", bingoBase());
        endpoints.put("BINGO_DETAIL", bingoBase() + "/" + BINGO_ID);
        endpoints.put("MEMBERS", "/api/communities/" + COMMUNITY_ID + "/members");
        for (Map.Entry<String, String> endpoint : endpoints.entrySet())
            call(client, "BEFORE_" + endpoint.getKey(), "GET", endpoint.getValue());

        HttpResult first = call(client, "AGGREGATE_FIRST", "POST", aggregatePath());
        if (first.status != 202) throw new IllegalStateException("First aggregate status=" + first.status);
        waitUntilRunning(client);
        // RUNNING is published before the worker enters aggregate(). Give it time to acquire
        // the production code's community/event locks before creating concurrent waiters.
        Thread.sleep(1_500);

        ExecutorService callers = Executors.newFixedThreadPool(24);
        List<CompletableFuture<HttpResult>> futures = new ArrayList<>();
        futures.add(async(callers, client, "AGGREGATE_DUPLICATE", "POST", aggregatePath()));
        futures.add(async(callers, client, "RUNNING_BINGO_LIST", "GET", bingoBase()));
        futures.add(async(callers, client, "RUNNING_BINGO_DETAIL", "GET", bingoBase() + "/" + BINGO_ID));
        for (int index = 1; index <= 10; index++)
            futures.add(async(callers, client, "RUNNING_BINGO_LOCK_WAITER_" + index,
                    "GET", bingoBase() + "/current"));

        Thread.sleep(500);
        futures.add(async(callers, client, "RUNNING_LOGIN_ME", "GET", "/api/auth/me"));
        futures.add(async(callers, client, "RUNNING_COMMUNITY", "GET", "/api/communities/" + COMMUNITY_ID));
        futures.add(async(callers, client, "RUNNING_MEMBERS", "GET", "/api/communities/" + COMMUNITY_ID + "/members"));

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(15, TimeUnit.MINUTES);
        callers.shutdown();
        callers.awaitTermination(10, TimeUnit.SECONDS);
        HttpResult finalStatus = call(client, "AFTER_AGGREGATION_STATUS", "GET", aggregatePath() + "/status");
        log.info("[BINGO_AGG_RESULT] statusBody={}", finalStatus.body);
        for (Map.Entry<String, String> endpoint : endpoints.entrySet())
            call(client, "AFTER_" + endpoint.getKey(), "GET", endpoint.getValue());
    }

    @Test
    void completeAggregationWithoutConcurrentLoad() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10)).build();
        call(client, "SESSION", "POST", "/__baseline/session");
        HttpResult first = call(client, "AGGREGATE_COMPLETION_RUN", "POST", aggregatePath());
        if (first.status != 202) throw new IllegalStateException("Aggregate status=" + first.status);

        Instant deadline = Instant.now().plus(Duration.ofMinutes(45));
        while (Instant.now().isBefore(deadline)) {
            HttpResult status = call(client, "AGGREGATION_COMPLETION_STATUS", "GET", aggregatePath() + "/status");
            if (status.body.contains("SUCCEEDED") || status.body.contains("FAILED")) {
                log.info("[BINGO_AGG_RESULT] statusBody={}", status.body);
                if (!status.body.contains("SUCCEEDED"))
                    throw new IllegalStateException("Aggregation failed: " + status.body);
                return;
            }
            Thread.sleep(2_000);
        }
        throw new IllegalStateException("Aggregation did not finish within 45 minutes");
    }

    private CompletableFuture<HttpResult> async(ExecutorService executor, HttpClient client,
                                                 String name, String method, String path) {
        return CompletableFuture.supplyAsync(() -> {
            try { return call(client, name, method, path); }
            catch (Exception exception) { throw new RuntimeException(exception); }
        }, executor);
    }

    private void waitUntilRunning(HttpClient client) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            HttpResult result = call(client, "AGGREGATION_STATUS", "GET", aggregatePath() + "/status");
            if (result.body.contains("RUNNING")) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("Aggregation did not enter RUNNING state");
    }

    private HttpResult call(HttpClient client, String name, String method, String path) throws Exception {
        Instant startedAt = Instant.now();
        long started = System.nanoTime();
        log.info("[BINGO_API] name={} requestStart={}", name, startedAt);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofMinutes(14));
        if ("POST".equals(method)) request.POST(HttpRequest.BodyPublishers.noBody());
        else request.GET();
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        log.info("[BINGO_API] name={} requestEnd={} elapsedMs={} httpStatus={}",
                name, Instant.now(), elapsedMs, response.statusCode());
        return new HttpResult(response.statusCode(), response.body(), elapsedMs);
    }

    private String bingoBase() {
        return "/api/communities/" + COMMUNITY_ID + "/games/" + GAME_ID + "/bingos";
    }

    private String aggregatePath() { return bingoBase() + "/" + BINGO_ID + "/aggregate"; }

    record HttpResult(int status, String body, long elapsedMs) { }

    @TestConfiguration
    static class SessionConfiguration {
        @Bean SessionController baselineSessionController() { return new SessionController(); }
    }

    @RestController
    static class SessionController {
        @PostMapping("/__baseline/session")
        void session(HttpSession session) { session.setAttribute(CurrentUserSession.USER_ID, USER_ID); }
    }
}
