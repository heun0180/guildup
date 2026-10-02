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

        writer.saveMatchIfAbsent(PubgPlatform.KAKAO, match);

        assertThat(players.findAll()).extracting(value -> value.getAccountId())
                .containsExactlyInAnyOrder("account-a", "account-b");
    }

    @Test void twoJvmLikeTelemetryWritersSerializeTheSameMatchWithoutDuplicateFacts() throws Exception {
        Instant at = Instant.parse("2026-09-25T00:00:00Z");
        PubgMatch match = new PubgMatch("shared-telemetry", at, "squad", "Erangel_Main",
                "official", false, "https://telemetry-cdn.pubg.com/shared-telemetry",
                List.of(new PubgTeam(List.of(new PubgParticipant("account-a", "A", 1)))));
        writer.saveMatchIfAbsent(PubgPlatform.KAKAO, match);
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

        assertThat(matches.findByShardAndMatchId("kakao","shared-telemetry").orElseThrow().isTelemetryLoaded()).isTrue();
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
                  {"_T":"LogPlayerKillV2","_D":"2026-09-25T00:01:00Z","attackId":1,"killer":{"accountId":"account-a","teamId":1},"victim":{"accountId":"ai.1001","teamId":2},"killerDamageInfo":{"damageCauserName":"WeapM24_C","damageReason":"HeadShot","distance":19999}},
                  {"_T":"LogPlayerKillV2","_D":"2026-09-25T00:02:00Z","attackId":2,"killer":{"accountId":"account-a","teamId":1},"victim":{"accountId":"v2","teamId":2},"killerDamageInfo":{"damageCauserName":"WeapM24_C","distance":20000}},
                  {"_T":"LogPlayerKillV2","_D":"2026-09-25T00:03:00Z","attackId":3,"killer":{"accountId":"account-a","teamId":1},"victim":{"accountId":"v3","teamId":2},"killerDamageInfo":{"damageCauserName":"WeapM24_C","distance":25000}}
                ]
                """));
        PubgBingoFactService parser = new PubgBingoFactService(telemetry, Clock.systemUTC());

        writer.saveMatchIfAbsent(PubgPlatform.KAKAO, match);
        writer.saveTelemetry(PubgPlatform.KAKAO,match.matchId(), parser.facts(PubgPlatform.KAKAO,match, Set.of("account-a")));

        assertThat(kills.findAll()).extracting(value -> value.getDistanceMeters())
                .containsExactlyInAnyOrder(new BigDecimal("199.990"), new BigDecimal("200.000"), new BigDecimal("250.000"));
        assertThat(kills.findAll()).filteredOn(value -> value.getVictimAccountId().startsWith("ai."))
                .singleElement().satisfies(value -> assertThat(value.isHeadshot()).isTrue());
        assertThat(matches.findByShardAndMatchId("kakao",match.matchId()).orElseThrow().getTelemetryFactVersion())
                .isEqualTo(com.guildup.pubg.domain.PubgStoredMatch.CURRENT_TELEMETRY_FACT_VERSION);
        PlayerMatchFacts stored = query.findBetween(PubgPlatform.KAKAO,at.minusSeconds(1), at.plusSeconds(1), Set.of("account-a"), Set.of("account-a"))
                .getFirst().byAccount().get("account-a");
        assertThat(stored.metric("NON_BOT_KILLS")).isEqualByComparingTo("2");
        assertThat(new BingoMissionEngine().value(BingoMissionType.LONG_DISTANCE_KILL, stored,
                Map.of("distance", 200))).isEqualByComparingTo("2");
    }

    @Test void sameMatchIdOnTwoPlatformsIsStoredQueriedAndUpdatedIndependently() {
        Instant at = Instant.parse("2026-09-25T00:00:00Z");
        PubgMatch kakao = new PubgMatch("match-123", at, "squad", "Erangel_Main", "official", false, null,
                List.of(new PubgTeam(List.of(new PubgParticipant("same-account", "Kakao", 3)))));
        PubgMatch steam = new PubgMatch("match-123", at, "squad", "Erangel_Main", "official", false, null,
                List.of(new PubgTeam(List.of(new PubgParticipant("same-account", "Steam", 8)))));
        assertThat(writer.saveMatchIfAbsent(PubgPlatform.KAKAO, kakao)).isTrue();
        assertThat(writer.saveMatchIfAbsent(PubgPlatform.STEAM, steam)).isTrue();
        assertThat(writer.saveMatchIfAbsent(PubgPlatform.KAKAO, kakao)).isFalse();
        writer.saveTelemetry(PubgPlatform.KAKAO, "match-123", Map.of());
        assertThat(matches.findByShardAndMatchId("kakao", "match-123").orElseThrow().isTelemetryLoaded()).isTrue();
        assertThat(matches.findByShardAndMatchId("steam", "match-123").orElseThrow().isTelemetryLoaded()).isFalse();
        assertThat(query.findTelemetryMissing(PubgPlatform.STEAM, Set.of("match-123")))
                .singleElement().satisfies(match -> assertThat(match.teams().getFirst().participants().getFirst().kills()).isEqualTo(8));
        assertThat(query.findBetween(PubgPlatform.KAKAO, at.minusSeconds(1), at.plusSeconds(1), Set.of("same-account"), Set.of()))
                .singleElement().satisfies(fact -> assertThat(fact.platform()).isEqualTo(PubgPlatform.KAKAO));
        assertThat(query.findBetween(PubgPlatform.KAKAO, at.minusSeconds(1), at.plusSeconds(1), Set.of("unrelated-account"), Set.of())).isEmpty();
        assertThat(matches.count()).isEqualTo(2);
    }

    @Test void originalTimeSurvivedAndRoadKillsSurviveFactDatabaseRoundTrip() {
        Instant at = Instant.parse("2026-09-25T00:00:00Z");
        PubgParticipant source = new PubgParticipant("account-a", "A", 4, 300, 2, 1, 1, 2, 3, 1, 2, 1,
                1234.567891, 100, 200, 10, 150);
        PubgMatch match = new PubgMatch("original-stats", at, "squad", "Erangel_Main", "official", false, null,
                List.of(new PubgTeam(List.of(source))));
        writer.saveMatchIfAbsent(PubgPlatform.STEAM, match);
        PubgParticipant restored = query.findTelemetryMissing(PubgPlatform.STEAM, Set.of(match.matchId()))
                .getFirst().teams().getFirst().participants().getFirst();
        assertThat(restored.survivalTime()).isEqualTo(source.survivalTime());
        assertThat(restored.roadKills()).isEqualTo(source.roadKills());
    }

    @Test void fallbackFactsAndFailedTelemetryNeverMarkTheStoredMatchLoaded() {
        Instant at = Instant.parse("2026-09-25T00:00:00Z");
        PubgMatch match = new PubgMatch("missing-telemetry", at, "squad", "Erangel_Main", "official", false, null,
                List.of(new PubgTeam(List.of(new PubgParticipant("account-a", "A", 4)))));
        PubgTelemetryClient telemetry = mock(PubgTelemetryClient.class);
        PubgBingoFactService parser = new PubgBingoFactService(telemetry, Clock.systemUTC());
        assertThat(parser.facts(PubgPlatform.KAKAO, match, Set.of()).get("account-a").metric("KILLS"))
                .isEqualByComparingTo("4");
        com.guildup.pubg.service.PubgPlayerService playerApi = mock(com.guildup.pubg.service.PubgPlayerService.class);
        com.guildup.pubg.service.PubgMatchService matchApi = mock(com.guildup.pubg.service.PubgMatchService.class);
        when(playerApi.findByAccountIdsFresh("kakao", List.of("account-a")))
                .thenReturn(List.of(new PubgPlayer("account-a", "A", List.of(match.matchId()))));
        when(matchApi.findUniqueMatches("kakao", Set.of(match.matchId()))).thenReturn(Map.of(match.matchId(), match));
        var collector = new com.guildup.pubg.service.PubgMatchSyncService(playerApi, matchApi, parser, query, writer, 1);
        try {
            var result = collector.sync(PubgPlatform.KAKAO, List.of("account-a"), ignored -> true,
                    com.guildup.pubg.service.PubgMatchSyncService.ProgressListener.noop());
            assertThat(result.telemetryFailures()).isEqualTo(1);
            assertThat(matches.findByShardAndMatchId("kakao", match.matchId()).orElseThrow().isTelemetryLoaded()).isFalse();
        } finally { org.springframework.test.util.ReflectionTestUtils.invokeMethod(collector, "shutdownExecutor"); }
    }

    private void saveAtTheSameTime(CyclicBarrier barrier, PubgMatch match) {
        try {
            barrier.await();
            writer.saveMatchIfAbsent(PubgPlatform.KAKAO, match);
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
        return writer.saveTelemetry(PubgPlatform.KAKAO,"shared-telemetry", facts);
    }
}
