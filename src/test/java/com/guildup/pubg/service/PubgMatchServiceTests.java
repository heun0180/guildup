package com.guildup.pubg.service;

import com.guildup.pubg.client.PubgApiClient;
import com.guildup.pubg.exception.PubgApiException;
import com.guildup.pubg.model.PubgMatch;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PubgMatchServiceTests {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC
    );

    @Test
    void requestsTheSameMatchOnlyOnce() {
        PubgApiClient client = mock(PubgApiClient.class);
        List<Long> waits = new ArrayList<>();
        PubgMatchService service = new PubgMatchService(
                client, FIXED_CLOCK, Duration.ofMinutes(10), waits::add
        );
        PubgMatch match = new PubgMatch("match-1", Instant.now(), "squad", List.of());
        when(client.getMatch("kakao", "match-1")).thenReturn(match);

        var result = service.findUniqueMatches("kakao", List.of("match-1", "match-1"));

        assertThat(result).containsEntry("match-1", match);
        assertThat(waits).isEmpty();
        verify(client, times(1)).getMatch("kakao", "match-1");
    }

    @Test
    void reusesMatchesWithinTheCacheTtl() {
        PubgApiClient client = mock(PubgApiClient.class);
        PubgMatch match = new PubgMatch("match-1", Instant.now(), "squad", List.of());
        when(client.getMatch("steam", "match-1")).thenReturn(match);
        PubgMatchService service = new PubgMatchService(
                client,
                Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(10)
        );

        assertThat(service.findUniqueMatches("steam", List.of("match-1")))
                .containsEntry("match-1", match);
        assertThat(service.findUniqueMatches("steam", List.of("match-1")))
                .containsEntry("match-1", match);

        verify(client, times(1)).getMatch("steam", "match-1");
    }

    @Test
    void freshLookupRetriesPreviouslyMissingMatch() {
        PubgApiClient client = mock(PubgApiClient.class);
        PubgMatch match = new PubgMatch("match-1", Instant.now(), "squad", List.of());
        when(client.getMatch("steam", "match-1")).thenReturn(null).thenReturn(match);
        PubgMatchService service = new PubgMatchService(client);

        assertThat(service.findUniqueMatches("steam", List.of("match-1"))).isEmpty();
        assertThat(service.findUniqueMatchesFresh("steam", List.of("match-1"))).containsEntry("match-1", match);
        verify(client, times(2)).getMatch("steam", "match-1");
    }

    @Test
    void retriesTransient503AndContinuesAfterSuccess() {
        PubgApiClient client = mock(PubgApiClient.class);
        PubgMatch match = new PubgMatch("match-38", Instant.now(), "squad", List.of());
        when(client.getMatch("kakao", "match-38"))
                .thenThrow(new PubgApiException("temporary", null, 503, true))
                .thenReturn(match);
        List<Long> waits = new ArrayList<>();
        PubgMatchService service = new PubgMatchService(
                client, FIXED_CLOCK, Duration.ofMinutes(10), waits::add
        );

        assertThat(service.findUniqueMatchesFresh(
                "kakao", List.of("match-38"), 1L, 3L
        )).containsEntry("match-38", match);

        assertThat(waits).containsExactly(500L);
        verify(client, times(2)).getMatch("kakao", "match-38");
    }

    @Test
    void retriesTransientNetworkFailureAndSucceeds() {
        PubgApiClient client = mock(PubgApiClient.class);
        PubgMatch match = new PubgMatch("match-1", Instant.now(), "squad", List.of());
        when(client.getMatch("steam", "match-1"))
                .thenThrow(new PubgApiException("read timeout", null, null, true))
                .thenReturn(match);
        List<Long> waits = new ArrayList<>();
        PubgMatchService service = new PubgMatchService(
                client, FIXED_CLOCK, Duration.ofMinutes(10), waits::add
        );

        assertThat(service.findUniqueMatchesFresh("steam", List.of("match-1")))
                .containsEntry("match-1", match);
        assertThat(waits).containsExactly(500L);
        verify(client, times(2)).getMatch("steam", "match-1");
    }

    @Test
    void failsAfterThreeTransientAttempts() {
        PubgApiClient client = mock(PubgApiClient.class);
        when(client.getMatch("kakao", "match-1"))
                .thenThrow(new PubgApiException("temporary", null, 503, true));
        List<Long> waits = new ArrayList<>();
        PubgMatchService service = new PubgMatchService(
                client, FIXED_CLOCK, Duration.ofMinutes(10), waits::add
        );

        assertThatThrownBy(() -> service.findUniqueMatchesFresh(
                "kakao", List.of("match-1"), 1L, 3L
        )).isInstanceOf(PubgApiException.class);

        assertThat(waits).containsExactly(500L, 1_000L);
        verify(client, times(3)).getMatch("kakao", "match-1");
    }

    @Test
    void missingMatchIsSkippedWithoutRetry() {
        PubgApiClient client = mock(PubgApiClient.class);
        when(client.getMatch("kakao", "missing")).thenReturn(null);
        List<Long> waits = new ArrayList<>();
        PubgMatchService service = new PubgMatchService(
                client, FIXED_CLOCK, Duration.ofMinutes(10), waits::add
        );

        assertThat(service.findUniqueMatchesFresh("kakao", List.of("missing"))).isEmpty();
        assertThat(waits).isEmpty();
        verify(client).getMatch("kakao", "missing");
    }

    @Test
    void clientAuthorizationErrorsAreNotRetried() {
        for (int status : List.of(401, 403)) {
            PubgApiClient client = mock(PubgApiClient.class);
            when(client.getMatch("kakao", "match-" + status))
                    .thenThrow(new PubgApiException("authorization", null, status, false));
            List<Long> waits = new ArrayList<>();
            PubgMatchService service = new PubgMatchService(
                    client, FIXED_CLOCK, Duration.ofMinutes(10), waits::add
            );

            assertThatThrownBy(() -> service.findUniqueMatchesFresh(
                    "kakao", List.of("match-" + status)
            )).isInstanceOf(PubgApiException.class);
            assertThat(waits).isEmpty();
            verify(client).getMatch("kakao", "match-" + status);
        }
    }
}
