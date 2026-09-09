package com.guildup.pubg.service;

import com.guildup.pubg.client.PubgApiClient;
import com.guildup.pubg.model.PubgSeason;
import com.guildup.pubg.model.PubgSeasonStats;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

class PubgSeasonServiceTests {
    private final PubgApiClient client = mock(PubgApiClient.class);
    private final PubgSeasonService service = new PubgSeasonService(
            client, Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void findsCurrentAndImmediatelyPreviousOfficialSeason() {
        when(client.getSeasons("kakao")).thenReturn(List.of(
                new PubgSeason("division.bro.official.pc-2026-01", false, false),
                new PubgSeason("division.bro.official.pc-2026-03", true, false),
                new PubgSeason("division.bro.official.pc-2026-02", false, false),
                new PubgSeason("division.bro.official.pc-2026-02-off", false, true)
        ));

        var pair = service.getCurrentAndPrevious("kakao");

        assertThat(pair.currentSeasonId()).isEqualTo("division.bro.official.pc-2026-03");
        assertThat(pair.previousSeasonId()).isEqualTo("division.bro.official.pc-2026-02");
    }

    @Test
    void usesOnlyCurrentSeasonWhenItIsTheOnlySelection() {
        when(client.getPlayersSeasonStats("kakao", List.of("account.1"), "current"))
                .thenReturn(Map.of("account.1", new PubgSeasonStats("account.1", 1_000, 4)));

        var stats = service.getCombinedStats("kakao", "account.1", List.of("current"));

        assertThat(stats.damageDealt()).isEqualTo(1_000);
        assertThat(stats.roundsPlayed()).isEqualTo(4);
    }

    @Test
    void usesOnlyPreviousSeasonWhenItIsTheOnlySelection() {
        when(client.getPlayersSeasonStats("steam", List.of("account.1"), "previous"))
                .thenReturn(Map.of("account.1", new PubgSeasonStats("account.1", 900, 3)));

        var stats = service.getCombinedStats("steam", "account.1", List.of("previous"));

        assertThat(stats.damageDealt()).isEqualTo(900);
        assertThat(stats.roundsPlayed()).isEqualTo(3);
    }

    @Test
    void combinesDamageAndRoundsAcrossBothSeasonsForWeightedAverage() {
        when(client.getPlayersSeasonStats("kakao", List.of("account.1"), "current"))
                .thenReturn(Map.of("account.1", new PubgSeasonStats("account.1", 1_000, 4)));
        when(client.getPlayersSeasonStats("kakao", List.of("account.1"), "previous"))
                .thenReturn(Map.of("account.1", new PubgSeasonStats("account.1", 600, 2)));

        var stats = service.getCombinedStats("kakao", "account.1", List.of("current", "previous"));

        assertThat(stats.damageDealt()).isEqualTo(1_600);
        assertThat(stats.roundsPlayed()).isEqualTo(6);
        assertThat(stats.damageDealt() / stats.roundsPlayed()).isCloseTo(266.67, within(0.01));
    }

    @Test
    void batchesEightyNinePlayersIntoNineRequestsAndCachesTheSeasonStats() {
        List<String> accountIds = IntStream.rangeClosed(1, 89)
                .mapToObj(number -> "account." + number).toList();
        when(client.getPlayersSeasonStats(eq("kakao"), anyList(), eq("current")))
                .thenAnswer(invocation -> invocation.<List<String>>getArgument(1).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                id -> id, id -> new PubgSeasonStats(id, 1_000, 4)
                        )));

        var first = service.getCombinedStats("kakao", accountIds, List.of("current"));
        var second = service.getCombinedStats("kakao", accountIds, List.of("current"));

        assertThat(first).hasSize(89);
        assertThat(second.get("account.89").roundsPlayed()).isEqualTo(4);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> batches = ArgumentCaptor.forClass(List.class);
        verify(client, times(9)).getPlayersSeasonStats(eq("kakao"), batches.capture(), eq("current"));
        assertThat(batches.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(10));
        assertThat(batches.getAllValues()).flatExtracting(batch -> batch).hasSize(89);
        verify(client, never()).getPlayerSeasonStats(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
