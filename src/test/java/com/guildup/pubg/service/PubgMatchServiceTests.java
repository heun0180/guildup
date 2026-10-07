package com.guildup.pubg.service;

import com.guildup.pubg.client.PubgApiClient;
import com.guildup.pubg.model.PubgMatch;
import com.guildup.monitoring.logging.ActivitySyncLog;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PubgMatchServiceTests {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC
    );

    @Test
    void thousandsOfSuccessfulMatchesPropagateSyncIdAndUseSummaryLogsAndCountCacheHits() {
        PubgApiClient client = mock(PubgApiClient.class);
        var monitoring = mock(MonitoringEventService.class);
        var operation = new ActivitySyncLog(monitoring, 1L, 2L, 3L, FIXED_CLOCK.instant());
        String syncId = ActivitySyncLog.syncId(2L, FIXED_CLOCK.instant());
        when(client.getMatch(anyString(), anyString())).thenAnswer(call -> {
            assertThat(org.slf4j.MDC.get("syncId")).isEqualTo(syncId);
            assertThat(ActivitySyncLog.current()).isSameAs(operation);
            operation.apiAttempt("MATCH", 0);
            return match(call.getArgument(1));
        });
        PubgMatchService service = service(client, 4000);
        List<String> ids = java.util.stream.IntStream.range(0, 3000).mapToObj(index -> "match-" + index).toList();
        try (var ignored = operation.open()) {
            operation.info(MonitoringEventCode.ACTIVITY_SYNC_MATCH_FETCH_STARTED, "시작", java.util.Map.of());
            assertThat(service.findUniqueMatchesFresh("kakao", ids)).hasSize(3000);
            assertThat(operation.matchMetrics()).containsEntry("apiCalls", 3000L).containsEntry("cacheHits", 0L);
            assertThat(service.findUniqueMatchesFresh("kakao", ids)).hasSize(3000);
            assertThat(operation.matchMetrics()).containsEntry("apiCalls", 3000L).containsEntry("cacheHits", 3000L);
            operation.info(MonitoringEventCode.ACTIVITY_SYNC_MATCH_FETCH_COMPLETED, "완료", operation.matchMetrics());
        } finally { service.shutdownExecutor(); }
        org.mockito.Mockito.verify(monitoring, times(2)).recordInfo(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(syncId), org.mockito.ArgumentMatchers.anyMap());
        org.mockito.Mockito.verifyNoMoreInteractions(monitoring);
    }

    @Test
    void requestsTheSameMatchOnlyOnce() {
        PubgApiClient client = mock(PubgApiClient.class);
        PubgMatch match = match("match-1");
        when(client.getMatch("kakao", "match-1")).thenReturn(match);
        PubgMatchService service = service(client, 100);

        assertThat(service.findUniqueMatches("kakao", List.of("match-1", "match-1")))
                .containsEntry("match-1", match);
        assertThat(service.findUniqueMatches("kakao", List.of("match-1")))
                .containsEntry("match-1", match);

        verify(client, times(1)).getMatch("kakao", "match-1");
    }

    @Test
    void freshLookupReusesSuccessfulMatchAcrossFeatures() {
        PubgApiClient client = mock(PubgApiClient.class);
        PubgMatch match = match("shared-match");
        when(client.getMatch("kakao", "shared-match")).thenReturn(match);
        PubgMatchService service = service(client, 100);

        assertThat(service.findUniqueMatches("kakao", List.of("shared-match"))).hasSize(1);
        assertThat(service.findUniqueMatchesFresh("kakao", List.of("shared-match"))).hasSize(1);
        assertThat(service.findUniqueMatchesFresh("kakao", List.of("shared-match"), 1L, 2L)).hasSize(1);

        verify(client, times(1)).getMatch("kakao", "shared-match");
    }

    @Test
    void freshLookupRetriesOnlyPreviouslyMissingMatch() {
        PubgApiClient client = mock(PubgApiClient.class);
        PubgMatch match = match("match-1");
        when(client.getMatch("steam", "match-1")).thenReturn(null).thenReturn(match);
        PubgMatchService service = service(client, 100);

        assertThat(service.findUniqueMatches("steam", List.of("match-1"))).isEmpty();
        assertThat(service.findUniqueMatchesFresh("steam", List.of("match-1")))
                .containsEntry("match-1", match);

        verify(client, times(2)).getMatch("steam", "match-1");
    }

    @Test
    void concurrentRequestsShareOneInFlightCall() throws Exception {
        PubgApiClient client = mock(PubgApiClient.class);
        PubgMatch match = match("match-1");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.getMatch("kakao", "match-1")).thenAnswer(ignored -> {
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return match;
        });
        PubgMatchService service = service(client, 100);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.findUniqueMatches("kakao", List.of("match-1")));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.findUniqueMatchesFresh("kakao", List.of("match-1")));
            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS)).containsEntry("match-1", match);
            assertThat(second.get(2, TimeUnit.SECONDS)).containsEntry("match-1", match);
        }

        verify(client, times(1)).getMatch("kakao", "match-1");
    }

    @Test
    void cacheNeverExceedsConfiguredMaximumSize() {
        PubgApiClient client = mock(PubgApiClient.class);
        for (int index = 0; index < 20; index++) {
            when(client.getMatch("kakao", "match-" + index)).thenReturn(match("match-" + index));
        }
        PubgMatchService service = service(client, 5);

        for (int index = 0; index < 20; index++) {
            service.findUniqueMatches("kakao", List.of("match-" + index));
        }

        assertThat(service.cacheSize()).isEqualTo(5);
    }

    @Test
    void concurrentJobsShareTheApplicationWideMatchLimit() throws Exception {
        PubgApiClient client = mock(PubgApiClient.class);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        when(client.getMatch(anyString(), anyString())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            entered.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            active.decrementAndGet();
            return match(invocation.getArgument(1));
        });
        PubgMatchService service = new PubgMatchService(
                client, FIXED_CLOCK, Duration.ofHours(24), Duration.ofMinutes(1), 100, 2);

        try (var callers = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(index -> callers.submit(() -> service.findUniqueMatches(
                            "kakao", List.of("global-" + index)))).toList();
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(maximum.get()).isEqualTo(2);
            release.countDown();
            for (var future : futures) assertThat(future.get(3, TimeUnit.SECONDS)).hasSize(1);
        }
        assertThat(maximum.get()).isEqualTo(2);
    }

    private PubgMatchService service(PubgApiClient client, int maximumSize) {
        return new PubgMatchService(
                client, FIXED_CLOCK, Duration.ofHours(24), Duration.ofMinutes(1), maximumSize
        );
    }

    private PubgMatch match(String id) {
        return new PubgMatch(id, Instant.parse("2026-09-07T00:00:00Z"), "squad", List.of());
    }
}
