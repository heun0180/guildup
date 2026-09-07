package com.guildup.pubg.service;

import com.guildup.pubg.client.PubgApiClient;
import com.guildup.pubg.model.PubgMatch;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PubgMatchServiceTests {

    @Test
    void requestsTheSameMatchOnlyOnce() {
        PubgApiClient client = mock(PubgApiClient.class);
        PubgMatchService service = new PubgMatchService(client);
        PubgMatch match = new PubgMatch("match-1", Instant.now(), "squad", List.of());
        when(client.getMatch("kakao", "match-1")).thenReturn(match);

        var result = service.findUniqueMatches("kakao", List.of("match-1", "match-1"));

        assertThat(result).containsEntry("match-1", match);
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
}
