package com.guildup.pubg.service;

import com.guildup.bingo.mission.PubgBingoFactService;
import com.guildup.pubg.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
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
