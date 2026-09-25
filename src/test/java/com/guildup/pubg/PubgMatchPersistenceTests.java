package com.guildup.pubg;

import com.guildup.bingo.domain.BingoMissionType;
import com.guildup.bingo.mission.BingoMissionEngine;
import com.guildup.bingo.mission.PubgBingoFactService;
import com.guildup.discord.bot.DiscordBot;
import com.guildup.pubg.model.*;
import com.guildup.pubg.repository.*;
import com.guildup.pubg.service.PubgMatchFactQueryService;
import com.guildup.pubg.service.PubgMatchFactWriter;
import com.guildup.pubg.service.PubgTelemetryClient;
import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pubg-persistence;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PubgMatchPersistenceTests {
    @Autowired PubgMatchFactWriter writer;
    @Autowired PubgStoredMatchRepository matches;
    @Autowired PubgStoredMatchPlayerRepository players;
    @Autowired PubgStoredMatchKillRepository kills;
    @Autowired PubgMatchFactQueryService query;
    @MockitoBean JDA jda;
    @MockitoBean DiscordBot bot;

    @BeforeEach void clean() { kills.deleteAll(); players.deleteAll(); matches.deleteAll(); }

    @Test void concurrentCollectorsCannotDuplicateAMatchOrPlayerFact() throws Exception {
        PubgMatch match = new PubgMatch("same-match", Instant.parse("2026-09-25T00:00:00Z"), "squad",
                "Erangel_Main", "official", false, null,
                List.of(new PubgTeam(List.of(new PubgParticipant("account-a", "A", 3)))));
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = List.of(
                    executor.submit(() -> saveAtTheSameTime(barrier, match)),
                    executor.submit(() -> saveAtTheSameTime(barrier, match)));
            for (Future<?> future : futures) future.get();
        }

        assertThat(matches.count()).isEqualTo(1);
        assertThat(players.count()).isEqualTo(1);
    }

    @Test void firstCollectorStoresEveryMatchParticipantForLaterAccountReuse() {
        PubgMatch match = new PubgMatch("shared-match", Instant.parse("2026-09-25T00:00:00Z"), "squad",
                "Erangel_Main", "official", false, null,
                List.of(new PubgTeam(List.of(
                        new PubgParticipant("account-a", "A", 3),
                        new PubgParticipant("account-b", "B", 1)))));

        writer.saveMatchIfAbsent("kakao", match);

        assertThat(players.findAll()).extracting(value -> value.getAccountId())
                .containsExactlyInAnyOrder("account-a", "account-b");
    }

    @Test void twoJvmLikeTelemetryWritersSerializeTheSameMatchWithoutDuplicateFacts() throws Exception {
        Instant at = Instant.parse("2026-09-25T00:00:00Z");
        PubgMatch match = new PubgMatch("shared-telemetry", at, "squad", "Erangel_Main",
                "official", false, "https://telemetry-cdn.pubg.com/shared-telemetry",
                List.of(new PubgTeam(List.of(new PubgParticipant("account-a", "A", 1)))));
        writer.saveMatchIfAbsent("kakao", match);
        PlayerMatchFacts fact = new PlayerMatchFacts("shared-telemetry", at, "Erangel_Main", "squad",
                Map.of("KILLS", BigDecimal.ONE),
                List.of(new PlayerMatchFacts.KillFact("victim", "VSS", "DMR", null, 200, false, at)),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, at);
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<PubgMatchFactWriter.StoredCounts>> futures = List.of(
                    executor.submit(() -> saveTelemetryAtTheSameTime(barrier, Map.of("account-a", fact))),
                    executor.submit(() -> saveTelemetryAtTheSameTime(barrier, Map.of("account-a", fact))));
            for (Future<?> future : futures) future.get(3, TimeUnit.SECONDS);
        }

        assertThat(matches.findByMatchId("shared-telemetry").orElseThrow().isTelemetryLoaded()).isTrue();
        assertThat(kills.count()).isEqualTo(1);
    }

    @Test void telemetryCentimetersRoundTripThroughDatabaseAsMetersAtTheMissionBoundary() throws Exception {
        Instant at = Instant.parse("2026-09-25T00:00:00Z");
        PubgMatch match = new PubgMatch("distance-boundary", at, "squad", "Erangel_Main",
                "official", false, "https://telemetry-cdn.pubg.com/distance-boundary",
                List.of(new PubgTeam(List.of(new PubgParticipant("account-a", "A", 3)))));
        PubgTelemetryClient telemetry = mock(PubgTelemetryClient.class);
        when(telemetry.get(anyString())).thenReturn(JsonMapper.builder().build().readTree("""
                [
                  {"_T":"LogPlayerKillV2","_D":"2026-09-25T00:01:00Z","attackId":1,"killer":{"accountId":"account-a","teamId":1},"victim":{"accountId":"v1","teamId":2},"killerDamageInfo":{"damageCauserName":"WeapM24_C","distance":19999}},
                  {"_T":"LogPlayerKillV2","_D":"2026-09-25T00:02:00Z","attackId":2,"killer":{"accountId":"account-a","teamId":1},"victim":{"accountId":"v2","teamId":2},"killerDamageInfo":{"damageCauserName":"WeapM24_C","distance":20000}},
                  {"_T":"LogPlayerKillV2","_D":"2026-09-25T00:03:00Z","attackId":3,"killer":{"accountId":"account-a","teamId":1},"victim":{"accountId":"v3","teamId":2},"killerDamageInfo":{"damageCauserName":"WeapM24_C","distance":25000}}
                ]
                """));
        PubgBingoFactService parser = new PubgBingoFactService(telemetry, Clock.systemUTC());

        writer.saveMatchIfAbsent("kakao", match);
        writer.saveTelemetry(match.matchId(), parser.facts(match, Set.of("account-a")));

        assertThat(kills.findAll()).extracting(value -> value.getDistanceMeters())
                .containsExactlyInAnyOrder(new BigDecimal("199.990"), new BigDecimal("200.000"), new BigDecimal("250.000"));
        PlayerMatchFacts stored = query.findBetween(at.minusSeconds(1), at.plusSeconds(1), Set.of("account-a"))
                .getFirst().byAccount().get("account-a");
        assertThat(new BingoMissionEngine().value(BingoMissionType.LONG_DISTANCE_KILL, stored,
                Map.of("distance", 200))).isEqualByComparingTo("2");
    }

    private void saveAtTheSameTime(CyclicBarrier barrier, PubgMatch match) {
        try {
            barrier.await();
            writer.saveMatchIfAbsent("kakao", match);
        } catch (DataIntegrityViolationException ignored) {
            // The database unique key is the final arbiter for a true race.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } catch (BrokenBarrierException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private PubgMatchFactWriter.StoredCounts saveTelemetryAtTheSameTime(
            CyclicBarrier barrier, Map<String, PlayerMatchFacts> facts) throws Exception {
        barrier.await();
        return writer.saveTelemetry("shared-telemetry", facts);
    }
}
