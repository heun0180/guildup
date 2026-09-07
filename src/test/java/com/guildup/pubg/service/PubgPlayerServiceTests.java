package com.guildup.pubg.service;

import com.guildup.pubg.client.PubgApiClient;
import com.guildup.pubg.model.PubgPlayer;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PubgPlayerServiceTests {

    @Test
    void deduplicatesAndSplitsPlayersIntoBatchesOfTen() {
        PubgApiClient client = mock(PubgApiClient.class);
        PubgPlayerService service = new PubgPlayerService(client);
        List<String> names = IntStream.rangeClosed(1, 11).mapToObj(index -> "player-" + index).toList();
        when(client.getPlayersByNames("kakao", names.subList(0, 10))).thenReturn(List.of());
        when(client.getPlayersByNames("kakao", names.subList(10, 11))).thenReturn(List.of());

        assertThat(service.findByNames("kakao", names)).isEmpty();

        verify(client).getPlayersByNames("kakao", names.subList(0, 10));
        verify(client).getPlayersByNames("kakao", names.subList(10, 11));
    }

    @Test
    void reusesPlayerLookupsWithinTheCacheTtl() {
        PubgApiClient client = mock(PubgApiClient.class);
        PubgPlayer player = new PubgPlayer("account-1", "PlayerOne", List.of("match-1"));
        List<String> accountIds = List.of("account-1");
        when(client.getPlayersByAccountIds("steam", accountIds)).thenReturn(List.of(player));
        PubgPlayerService service = new PubgPlayerService(
                client,
                Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(1)
        );

        assertThat(service.findByAccountIds("steam", accountIds)).containsExactly(player);
        assertThat(service.findByAccountIds("steam", accountIds)).containsExactly(player);

        verify(client, times(1)).getPlayersByAccountIds("steam", accountIds);
    }

    @Test
    void cachesMissingPlayersToAvoidRepeatedRateLimitedRequests() {
        PubgApiClient client = mock(PubgApiClient.class);
        List<String> names = List.of("UnknownPlayer");
        when(client.getPlayersByNames("steam", names)).thenReturn(List.of());
        PubgPlayerService service = new PubgPlayerService(
                client,
                Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(1)
        );

        assertThat(service.findByNames("steam", names)).isEmpty();
        assertThat(service.findByNames("steam", names)).isEmpty();

        verify(client, times(1)).getPlayersByNames("steam", names);
    }
}
