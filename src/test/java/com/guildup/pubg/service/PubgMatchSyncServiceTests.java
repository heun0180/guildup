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

    @Test void sameMatchIdOnDifferentPlatformsHasIndependentInFlightTelemetry() throws Exception {
        var match = match("same-id");
        when(players.findByAccountIdsFresh(anyString(), anyList())).thenReturn(List.of(new PubgPlayer("account-a", "A", List.of("same-id"))));
        when(query.existingMatchIds(any(PubgPlatform.class), anyCollection())).thenReturn(Set.of("same-id"));
        when(query.findTelemetryMissing(any(PubgPlatform.class), anyCollection())).thenReturn(List.of(match));
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(facts.factsRequired(any(PubgPlatform.class), eq(match), anySet())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return Map.of("account-a", fact("same-id"));
        });
        when(writer.saveTelemetry(any(PubgPlatform.class), eq("same-id"), anyMap())).thenReturn(new PubgMatchFactWriter.StoredCounts(1, 0));
        try (var callers = Executors.newFixedThreadPool(2)) {
            var kakao = callers.submit(() -> sync.sync(PubgPlatform.KAKAO, List.of("account-a"), ignored -> true, PubgMatchSyncService.ProgressListener.noop()));
            var steam = callers.submit(() -> sync.sync(PubgPlatform.STEAM, List.of("account-a"), ignored -> true, PubgMatchSyncService.ProgressListener.noop()));
            try { assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            assertThat(kakao.get(5, TimeUnit.SECONDS).telemetryFailures()).isZero();
            assertThat(steam.get(5, TimeUnit.SECONDS).telemetryFailures()).isZero();
        }
        verify(writer).saveTelemetry(eq(PubgPlatform.KAKAO), eq("same-id"), anyMap());
        verify(writer).saveTelemetry(eq(PubgPlatform.STEAM), eq("same-id"), anyMap());
    }

    @Test void newMatchIsFetchedParsedAndStoredOnceOutsideATransaction() {
        PubgMatch match = match("A");
        when(players.findByAccountIdsFresh("kakao", List.of("account-a"))).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of(new PubgPlayer("account-a", "A", List.of("A")));
        });
        when(query.existingMatchIds(PubgPlatform.KAKAO,Set.of("A"))).thenReturn(Set.of());
        when(matches.findUniqueMatches("kakao", Set.of("A"))).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Map.of("A", match);
        });
        when(writer.saveMatchIfAbsent(PubgPlatform.KAKAO, match)).thenReturn(true);
        when(query.findTelemetryMissing(PubgPlatform.KAKAO,Set.of("A"))).thenReturn(List.of(match));
        when(facts.factsRequired(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),eq(match), anySet())).thenAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Map.of("account-a", fact("A"));
        });
        when(writer.saveTelemetry(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),eq("A"), anyMap())).thenReturn(new PubgMatchFactWriter.StoredCounts(1, 1));

        PubgMatchSyncService.SyncResult result = sync.sync(PubgPlatform.KAKAO, List.of("account-a"), value -> true,
                PubgMatchSyncService.ProgressListener.noop());

        assertThat(result.newMatchIds()).isEqualTo(1);
        assertThat(result.matchApiCalls()).isEqualTo(1);
        assertThat(result.telemetryApiCalls()).isEqualTo(1);
        verify(writer).saveMatchIfAbsent(PubgPlatform.KAKAO, match);
        verify(writer).saveTelemetry(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),eq("A"), anyMap());
    }

    @Test void storedMatchSkipsBothMatchAndTelemetryApis() {
        when(players.findByAccountIdsFresh(anyString(), anyList())).thenReturn(
                List.of(new PubgPlayer("account-a", "A", List.of("A"))));
        when(query.existingMatchIds(PubgPlatform.KAKAO,Set.of("A"))).thenReturn(Set.of("A"));
        when(query.findTelemetryMissing(PubgPlatform.KAKAO,Set.of("A"))).thenReturn(List.of());

        PubgMatchSyncService.SyncResult result = sync.sync(PubgPlatform.KAKAO, List.of("account-a"), value -> true,
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
        when(query.existingMatchIds(PubgPlatform.KAKAO,Set.of("X"))).thenReturn(Set.of());
        when(matches.findUniqueMatches(eq("kakao"), anyCollection())).thenReturn(Map.of("X", match("X")));
        when(writer.saveMatchIfAbsent(any(PubgPlatform.class), any())).thenReturn(true);
        when(query.findTelemetryMissing(PubgPlatform.KAKAO,Set.of("X"))).thenReturn(List.of());

        sync.sync(PubgPlatform.KAKAO, List.of("a", "b", "c"), value -> false, PubgMatchSyncService.ProgressListener.noop());

        verify(matches).findUniqueMatches(eq("kakao"), argThat(ids -> ids.size() == 1 && ids.contains("X")));
    }

    @Test void concurrentJobsShareTheApplicationWideTelemetryLimit() throws Exception {
        sync = new PubgMatchSyncService(players, matches, facts, query, writer, 2);
        when(players.findByAccountIdsFresh(eq("kakao"), anyList())).thenAnswer(invocation -> {
            String account = ((List<String>) invocation.getArgument(1)).getFirst();
            return List.of(new PubgPlayer(account, account, List.of("match-" + account)));
        });
        when(query.existingMatchIds(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),anyCollection())).thenReturn(Set.of());
        when(matches.findUniqueMatches(eq("kakao"), anyCollection())).thenAnswer(invocation -> {
            String id = ((Collection<String>) invocation.getArgument(1)).iterator().next();
            return Map.of(id, match(id));
        });
        when(writer.saveMatchIfAbsent(any(PubgPlatform.class), any())).thenReturn(true);
        when(query.findTelemetryMissing(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),anyCollection())).thenAnswer(invocation -> {
            String id = ((Collection<String>) invocation.getArgument(1)).iterator().next();
            return List.of(match(id));
        });
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(facts.factsRequired(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),any(), anySet())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            active.decrementAndGet();
            PubgMatch value = invocation.getArgument(1);
            return Map.of("account", fact(value.matchId()));
        });
        when(writer.saveTelemetry(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),anyString(), anyMap())).thenReturn(new PubgMatchFactWriter.StoredCounts(1, 1));

        try (var callers = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 8).mapToObj(index -> callers.submit(() ->
                    sync.sync(PubgPlatform.KAKAO, List.of("account-" + index), ignored -> true,
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
        verify(facts, times(10)).factsRequired(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),any(), anySet());
        verify(writer, times(9)).saveTelemetry(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),anyString(), anyMap());
    }

    @Test void telemetryTimeoutIsIsolatedFromOtherMatches() {
        PubgMatchSyncService.SyncResult result = runTenTelemetryTasksWithFailure(
                new PubgApiException("timeout", new HttpTimeoutException("read timeout")));

        assertThat(result.telemetryFailures()).isEqualTo(1);
        verify(writer, times(9)).saveTelemetry(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),anyString(), anyMap());
    }

    @Test void telemetryJsonParsingFailureIsIsolatedFromOtherMatches() {
        PubgMatchSyncService.SyncResult result = runTenTelemetryTasksWithFailure(
                new IllegalArgumentException("invalid telemetry json"));

        assertThat(result.telemetryFailures()).isEqualTo(1);
        verify(writer, times(9)).saveTelemetry(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),anyString(), anyMap());
    }

    @Test void wrappedHttpJsonFailureIsReportedAsParsingRatherThanFetching() {
        var monitoring = mock(com.guildup.monitoring.service.MonitoringEventService.class);
        sync.configureMonitoring(monitoring);
        var result = runTenTelemetryTasksWithFailure(new PubgApiException("invalid telemetry response",
                new org.springframework.http.converter.HttpMessageConversionException("invalid JSON")));

        assertThat(result.telemetryFailures()).isEqualTo(1);
        verify(writer, times(9)).saveTelemetry(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),anyString(), anyMap());
        verify(monitoring).recordError(eq(com.guildup.monitoring.domain.MonitoringCategory.PUBG_API),
                eq(com.guildup.monitoring.domain.MonitoringEventCode.PUBG_API_FAILED), anyString(),
                isNull(), isNull(), eq("matchId=M0"),
                argThat(metadata -> "TELEMETRY_PARSE".equals(metadata.get("stage"))));
    }

    @Test void failedStoredMatchRetriesTelemetryWithoutCallingMatchApiAgain() {
        PubgMatch stored = match("retry");
        when(players.findByAccountIdsFresh("kakao", List.of("account-a"))).thenReturn(
                List.of(new PubgPlayer("account-a", "A", List.of())));
        when(query.findTelemetryMissingForAccounts(PubgPlatform.KAKAO,List.of("account-a"))).thenReturn(List.of(stored));
        when(facts.factsRequired(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),eq(stored), anySet()))
                .thenThrow(new PubgApiException("timeout", new HttpTimeoutException("read timeout")))
                .thenReturn(Map.of("account-a", fact("retry")));
        when(writer.saveTelemetry(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),eq("retry"), anyMap())).thenReturn(new PubgMatchFactWriter.StoredCounts(1, 1));

        PubgMatchSyncService.SyncResult first = sync.sync(PubgPlatform.KAKAO, List.of("account-a"), value -> true,
                PubgMatchSyncService.ProgressListener.noop());
        PubgMatchSyncService.SyncResult second = sync.sync(PubgPlatform.KAKAO, List.of("account-a"), value -> true,
                PubgMatchSyncService.ProgressListener.noop());

        assertThat(first.telemetryFailures()).isEqualTo(1);
        assertThat(second.telemetryFailures()).isZero();
        verifyNoInteractions(matches);
        verify(facts, times(2)).factsRequired(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),eq(stored), anySet());
        verify(writer).saveTelemetry(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),eq("retry"), anyMap());
    }

    @Test void databaseWritesAlsoShareTheApplicationWideTelemetryLimit() throws Exception {
        sync = new PubgMatchSyncService(players, matches, facts, query, writer, 2);
        when(players.findByAccountIdsFresh(eq("kakao"), anyList())).thenAnswer(invocation -> {
            String account = ((List<String>) invocation.getArgument(1)).getFirst();
            return List.of(new PubgPlayer(account, account, List.of("match-" + account)));
        });
        when(query.existingMatchIds(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),anyCollection())).thenReturn(Set.of());
        when(matches.findUniqueMatches(eq("kakao"), anyCollection())).thenAnswer(invocation -> {
            String id = ((Collection<String>) invocation.getArgument(1)).iterator().next();
            return Map.of(id, match(id));
        });
        when(writer.saveMatchIfAbsent(any(PubgPlatform.class), any())).thenReturn(true);
        when(query.findTelemetryMissing(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),anyCollection())).thenAnswer(invocation -> {
            String id = ((Collection<String>) invocation.getArgument(1)).iterator().next();
            return List.of(match(id));
        });
        when(facts.factsRequired(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),any(), anySet())).thenAnswer(invocation -> {
            PubgMatch value = invocation.getArgument(1);
            return Map.of("account-a", fact(value.matchId()));
        });
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(writer.saveTelemetry(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),anyString(), anyMap())).thenAnswer(ignored -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            active.decrementAndGet();
            return new PubgMatchFactWriter.StoredCounts(1, 1);
        });

        try (var callers = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 8).mapToObj(index -> callers.submit(() ->
                    sync.sync(PubgPlatform.KAKAO, List.of("account-" + index), ignored -> true,
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
        when(query.existingMatchIds(PubgPlatform.KAKAO,Set.of("race"))).thenReturn(Set.of());
        when(matches.findUniqueMatches("kakao", Set.of("race"))).thenReturn(Map.of("race", value));
        when(writer.saveMatchIfAbsent(PubgPlatform.KAKAO, value)).thenThrow(new DataIntegrityViolationException("concurrent insert"));
        when(query.findTelemetryMissing(PubgPlatform.KAKAO,Set.of("race"))).thenReturn(List.of());

        PubgMatchSyncService.SyncResult result = sync.sync(PubgPlatform.KAKAO, List.of("account-a"), ignored -> false,
                PubgMatchSyncService.ProgressListener.noop());

        assertThat(result.dbMatchesInserted()).isZero();
        assertThat(result.telemetryFailures()).isZero();
    }

    @Test void factSaveFailureHasDatabaseHistoryAndStackWhileOtherMatchesStillComplete() {
        var monitoring = mock(com.guildup.monitoring.service.MonitoringEventService.class);
        sync.configureMonitoring(monitoring);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PubgMatchSyncService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        when(players.findByAccountIdsFresh("kakao", List.of("account-a"))).thenReturn(
                List.of(new PubgPlayer("account-a", "A", List.of("bad", "good"))));
        when(query.existingMatchIds(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),anyCollection())).thenReturn(Set.of("bad", "good"));
        when(query.findTelemetryMissing(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),anyCollection())).thenReturn(List.of(match("bad"), match("good")));
        when(facts.factsRequired(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),any(), anySet())).thenAnswer(invocation -> {
            assertThat(org.slf4j.MDC.get("requestId")).isEqualTo("req-telemetry-test");
            PubgMatch value = invocation.getArgument(1);
            return Map.of("account-a", fact(value.matchId()));
        });
        when(writer.saveTelemetry(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),eq("bad"), anyMap())).thenThrow(new DataIntegrityViolationException("fact constraint"));
        when(writer.saveTelemetry(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),eq("good"), anyMap())).thenReturn(new PubgMatchFactWriter.StoredCounts(1, 1));
        try {
            org.slf4j.MDC.put("requestId", "req-telemetry-test");
            var result = sync.sync(PubgPlatform.KAKAO, List.of("account-a"), value -> true, PubgMatchSyncService.ProgressListener.noop());
            assertThat(result.telemetryFailures()).isEqualTo(1);
            verify(writer).saveTelemetry(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),eq("good"), anyMap());
            verify(monitoring).recordError(eq(com.guildup.monitoring.domain.MonitoringCategory.DATABASE),
                    eq(com.guildup.monitoring.domain.MonitoringEventCode.DATABASE_ERROR), anyString(), isNull(), isNull(),
                    eq("matchId=bad"), argThat(metadata -> "TELEMETRY_FACT_SAVE".equals(metadata.get("stage"))));
            assertThat(appender.list).filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR)
                    .singleElement().satisfies(event -> {
                        assertThat(event.getFormattedMessage()).contains("matchId=bad", "stage=TELEMETRY_FACT_SAVE");
                        assertThat(event.getThrowableProxy()).isNotNull();
                    });
        } finally { org.slf4j.MDC.clear(); logger.detachAppender(appender); appender.stop(); }
    }

    private PubgMatchSyncService.SyncResult runTenTelemetryTasksWithFailure(RuntimeException failure) {
        List<PubgMatch> rows = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> match("M" + index)).toList();
        List<String> ids = rows.stream().map(PubgMatch::matchId).toList();
        when(players.findByAccountIdsFresh("kakao", List.of("account-a"))).thenReturn(
                List.of(new PubgPlayer("account-a", "A", ids)));
        when(query.existingMatchIds(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),anyCollection())).thenReturn(Set.copyOf(ids));
        when(query.findTelemetryMissing(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),anyCollection())).thenReturn(rows);
        when(facts.factsRequired(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),any(), anySet())).thenAnswer(invocation -> {
            PubgMatch value = invocation.getArgument(1);
            if (value.matchId().equals("M0")) throw failure;
            return Map.of("account-a", fact(value.matchId()));
        });
        when(writer.saveTelemetry(org.mockito.ArgumentMatchers.eq(PubgPlatform.KAKAO),anyString(), anyMap())).thenReturn(new PubgMatchFactWriter.StoredCounts(1, 1));
        AtomicInteger completed = new AtomicInteger();

        PubgMatchSyncService.SyncResult result = sync.sync(PubgPlatform.KAKAO, List.of("account-a"), value -> true,
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
