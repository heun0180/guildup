package com.guildup.pubg.service;

import com.guildup.bingo.mission.PubgBingoFactService;
import com.guildup.pubg.exception.PubgApiException;
import com.guildup.pubg.model.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PubgMatchSyncServiceTests {
    @Mock PubgPlayerService players;
    @Mock PubgMatchService matches;
    @Mock PubgMatchFactProvider facts;
    @Mock PubgMatchFactQueryService query;
    @Mock PubgMatchFactWriter writer;
    PubgMatchSyncService sync;

    @BeforeEach void setUp() { sync = new PubgMatchSyncService(players, matches, facts, query, writer, 3); }

    @Test void newMatchIsFetchedParsedAndStoredOnceOutsideATransaction() {
        PubgMatch match = match("A");
        when(players.findByAccountIdsFresh("kakao", List.of("account-a"))).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of(new PubgPlayer("account-a", "A", List.of("A")));
        });
        when(query.existingMatchIds(Set.of("A"))).thenReturn(Set.of());
        when(matches.findUniqueMatches("kakao", Set.of("A"))).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Map.of("A", match);
        });
        when(writer.saveMatchIfAbsent("kakao", match)).thenReturn(true);
        when(query.findTelemetryMissing(Set.of("A"))).thenReturn(List.of(match));
        when(facts.facts(eq(match), anySet())).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Map.of("account-a", fact("A"));
        });
        when(writer.saveTelemetry(eq("A"), anyMap())).thenReturn(new PubgMatchFactWriter.StoredCounts(1, 1));

        PubgMatchSyncService.SyncResult result = sync.sync("kakao", List.of("account-a"), value -> true,
                PubgMatchSyncService.ProgressListener.noop());

        assertThat(result.newMatchIds()).isEqualTo(1);
        assertThat(result.matchApiCalls()).isEqualTo(1);
        assertThat(result.telemetryApiCalls()).isEqualTo(1);
        verify(writer).saveMatchIfAbsent("kakao", match);
        verify(writer).saveTelemetry(eq("A"), anyMap());
    }

    @Test void storedMatchSkipsBothMatchAndTelemetryApis() {
        when(players.findByAccountIdsFresh(anyString(), anyList())).thenReturn(
                List.of(new PubgPlayer("account-a", "A", List.of("A"))));
        when(query.existingMatchIds(Set.of("A"))).thenReturn(Set.of("A"));
        when(query.findTelemetryMissing(Set.of("A"))).thenReturn(List.of());

        PubgMatchSyncService.SyncResult result = sync.sync("kakao", List.of("account-a"), value -> true,
                PubgMatchSyncService.ProgressListener.noop());

        assertThat(result.existingDbMatches()).isEqualTo(1);
        assertThat(result.matchApiCalls()).isZero();
        assertThat(result.telemetryApiCalls()).isZero();
        verifyNoInteractions(matches, facts, writer);
    }

    @Test void participantsSharingAMatchProduceOneMatchRequest() {
        when(players.findByAccountIdsFresh(eq("kakao"), anyList())).thenReturn(List.of(
                new PubgPlayer("a", "A", List.of("X")),
                new PubgPlayer("b", "B", List.of("X")),
                new PubgPlayer("c", "C", List.of("X"))));
        when(query.existingMatchIds(Set.of("X"))).thenReturn(Set.of());
        when(matches.findUniqueMatches(eq("kakao"), anyCollection())).thenReturn(Map.of("X", match("X")));
        when(writer.saveMatchIfAbsent(anyString(), any())).thenReturn(true);
        when(query.findTelemetryMissing(Set.of("X"))).thenReturn(List.of());

        sync.sync("kakao", List.of("a", "b", "c"), value -> false, PubgMatchSyncService.ProgressListener.noop());

        verify(matches).findUniqueMatches(eq("kakao"), argThat(ids -> ids.size() == 1 && ids.contains("X")));
    }

    @Test void concurrentJobsShareTheApplicationWideTelemetryLimit() throws Exception {
        sync = new PubgMatchSyncService(players, matches, facts, query, writer, 2);
        when(players.findByAccountIdsFresh(eq("kakao"), anyList())).thenAnswer(invocation -> {
            String account = ((List<String>) invocation.getArgument(1)).getFirst();
            return List.of(new PubgPlayer(account, account, List.of("match-" + account)));
        });
        when(query.existingMatchIds(anyCollection())).thenReturn(Set.of());
        when(matches.findUniqueMatches(eq("kakao"), anyCollection())).thenAnswer(invocation -> {
            String id = ((Collection<String>) invocation.getArgument(1)).iterator().next();
            return Map.of(id, match(id));
        });
        when(writer.saveMatchIfAbsent(anyString(), any())).thenReturn(true);
        when(query.findTelemetryMissing(anyCollection())).thenAnswer(invocation -> {
            String id = ((Collection<String>) invocation.getArgument(0)).iterator().next();
            return List.of(match(id));
        });
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(facts.facts(any(), anySet())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            active.decrementAndGet();
            PubgMatch value = invocation.getArgument(0);
            return Map.of("account", fact(value.matchId()));
        });
        when(writer.saveTelemetry(anyString(), anyMap())).thenReturn(new PubgMatchFactWriter.StoredCounts(1, 1));

        try (var callers = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 8).mapToObj(index -> callers.submit(() ->
                    sync.sync("kakao", List.of("account-" + index), ignored -> true,
                            PubgMatchSyncService.ProgressListener.noop()))).toList();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(maximum.get()).isEqualTo(2);
            release.countDown();
            for (var future : futures) future.get(3, TimeUnit.SECONDS);
        }
        assertThat(maximum.get()).isEqualTo(2);
    }

    @Test void firstHttp500FailureDoesNotStopTheOtherNineTelemetryTasks() {
        PubgMatchSyncService.SyncResult result = runTenTelemetryTasksWithFailure(
                new PubgApiException("upstream failed", new RuntimeException("HTTP 500"), 500, true));

        assertThat(result.telemetryApiCalls()).isEqualTo(10);
        assertThat(result.telemetryFailures()).isEqualTo(1);
        verify(facts, times(10)).facts(any(), anySet());
        verify(writer, times(9)).saveTelemetry(anyString(), anyMap());
    }

    @Test void telemetryTimeoutIsIsolatedFromOtherMatches() {
        PubgMatchSyncService.SyncResult result = runTenTelemetryTasksWithFailure(
                new PubgApiException("timeout", new HttpTimeoutException("read timeout")));

        assertThat(result.telemetryFailures()).isEqualTo(1);
        verify(writer, times(9)).saveTelemetry(anyString(), anyMap());
    }

    @Test void telemetryJsonParsingFailureIsIsolatedFromOtherMatches() {
        PubgMatchSyncService.SyncResult result = runTenTelemetryTasksWithFailure(
                new IllegalArgumentException("invalid telemetry json"));

        assertThat(result.telemetryFailures()).isEqualTo(1);
        verify(writer, times(9)).saveTelemetry(anyString(), anyMap());
    }

    @Test void failedStoredMatchRetriesTelemetryWithoutCallingMatchApiAgain() {
        PubgMatch stored = match("retry");
        when(players.findByAccountIdsFresh("kakao", List.of("account-a"))).thenReturn(
                List.of(new PubgPlayer("account-a", "A", List.of())));
        when(query.findTelemetryMissingForAccounts(List.of("account-a"))).thenReturn(List.of(stored));
        when(facts.facts(eq(stored), anySet()))
                .thenThrow(new PubgApiException("timeout", new HttpTimeoutException("read timeout")))
                .thenReturn(Map.of("account-a", fact("retry")));
        when(writer.saveTelemetry(eq("retry"), anyMap())).thenReturn(new PubgMatchFactWriter.StoredCounts(1, 1));

        PubgMatchSyncService.SyncResult first = sync.sync("kakao", List.of("account-a"), value -> true,
                PubgMatchSyncService.ProgressListener.noop());
        PubgMatchSyncService.SyncResult second = sync.sync("kakao", List.of("account-a"), value -> true,
                PubgMatchSyncService.ProgressListener.noop());

        assertThat(first.telemetryFailures()).isEqualTo(1);
        assertThat(second.telemetryFailures()).isZero();
        verifyNoInteractions(matches);
        verify(facts, times(2)).facts(eq(stored), anySet());
        verify(writer).saveTelemetry(eq("retry"), anyMap());
    }

    @Test void databaseWritesAlsoShareTheApplicationWideTelemetryLimit() throws Exception {
        sync = new PubgMatchSyncService(players, matches, facts, query, writer, 2);
        when(players.findByAccountIdsFresh(eq("kakao"), anyList())).thenAnswer(invocation -> {
            String account = ((List<String>) invocation.getArgument(1)).getFirst();
            return List.of(new PubgPlayer(account, account, List.of("match-" + account)));
        });
        when(query.existingMatchIds(anyCollection())).thenReturn(Set.of());
        when(matches.findUniqueMatches(eq("kakao"), anyCollection())).thenAnswer(invocation -> {
            String id = ((Collection<String>) invocation.getArgument(1)).iterator().next();
            return Map.of(id, match(id));
        });
        when(writer.saveMatchIfAbsent(anyString(), any())).thenReturn(true);
        when(query.findTelemetryMissing(anyCollection())).thenAnswer(invocation -> {
            String id = ((Collection<String>) invocation.getArgument(0)).iterator().next();
            return List.of(match(id));
        });
        when(facts.facts(any(), anySet())).thenAnswer(invocation -> {
            PubgMatch value = invocation.getArgument(0);
            return Map.of("account-a", fact(value.matchId()));
        });
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(writer.saveTelemetry(anyString(), anyMap())).thenAnswer(ignored -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            active.decrementAndGet();
            return new PubgMatchFactWriter.StoredCounts(1, 1);
        });

        try (var callers = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 8).mapToObj(index -> callers.submit(() ->
                    sync.sync("kakao", List.of("account-" + index), ignored -> true,
                            PubgMatchSyncService.ProgressListener.noop()))).toList();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(maximum.get()).isEqualTo(2);
            release.countDown();
            for (var future : futures) future.get(3, TimeUnit.SECONDS);
        }
        assertThat(maximum.get()).isEqualTo(2);
    }

    @Test void concurrentMatchInsertRaceDoesNotFailTheWholeSync() {
        PubgMatch value = match("race");
        when(players.findByAccountIdsFresh("kakao", List.of("account-a"))).thenReturn(
                List.of(new PubgPlayer("account-a", "A", List.of("race"))));
        when(query.existingMatchIds(Set.of("race"))).thenReturn(Set.of());
        when(matches.findUniqueMatches("kakao", Set.of("race"))).thenReturn(Map.of("race", value));
        when(writer.saveMatchIfAbsent("kakao", value)).thenThrow(new DataIntegrityViolationException("concurrent insert"));
        when(query.findTelemetryMissing(Set.of("race"))).thenReturn(List.of());

        PubgMatchSyncService.SyncResult result = sync.sync("kakao", List.of("account-a"), ignored -> false,
                PubgMatchSyncService.ProgressListener.noop());

        assertThat(result.dbMatchesInserted()).isZero();
        assertThat(result.telemetryFailures()).isZero();
    }

    private PubgMatchSyncService.SyncResult runTenTelemetryTasksWithFailure(RuntimeException failure) {
        List<PubgMatch> rows = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> match("M" + index)).toList();
        List<String> ids = rows.stream().map(PubgMatch::matchId).toList();
        when(players.findByAccountIdsFresh("kakao", List.of("account-a"))).thenReturn(
                List.of(new PubgPlayer("account-a", "A", ids)));
        when(query.existingMatchIds(anyCollection())).thenReturn(Set.copyOf(ids));
        when(query.findTelemetryMissing(anyCollection())).thenReturn(rows);
        when(facts.facts(any(), anySet())).thenAnswer(invocation -> {
            PubgMatch value = invocation.getArgument(0);
            if (value.matchId().equals("M0")) throw failure;
            return Map.of("account-a", fact(value.matchId()));
        });
        when(writer.saveTelemetry(anyString(), anyMap())).thenReturn(new PubgMatchFactWriter.StoredCounts(1, 1));
        AtomicInteger completed = new AtomicInteger();

        PubgMatchSyncService.SyncResult result = sync.sync("kakao", List.of("account-a"), value -> true,
                (stage, current, total, message) -> {
                    if ("TELEMETRY_FETCH".equals(stage)) completed.accumulateAndGet(current, Math::max);
                });

        assertThat(completed.get()).isEqualTo(10);
        return result;
    }

    private PubgMatch match(String id) {
        return new PubgMatch(id, Instant.parse("2026-09-25T00:00:00Z"), "squad", "Erangel_Main",
                "official", false, "https://telemetry-cdn.pubg.com/" + id,
                List.of(new PubgTeam(List.of(new PubgParticipant("account-a", "A", 1)))));
    }
    private PlayerMatchFacts fact(String id) {
        Instant at = Instant.parse("2026-09-25T00:00:00Z");
        return new PlayerMatchFacts(id, at, "Erangel_Main", "squad", Map.of("KILLS", BigDecimal.ONE),
                List.of(new PlayerMatchFacts.KillFact("victim", "VSS", "DMR", null, 250, false, at)),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, at);
    }
}
