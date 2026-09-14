package com.guildup.killcompetition;

import com.guildup.killcompetition.service.*;
import com.guildup.pubg.exception.PubgApiException;
import com.guildup.pubg.model.*;
import com.guildup.pubg.service.*;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KillCompetitionPubgAggregatorTests {
    PubgPlayerService players = Mockito.mock(PubgPlayerService.class);
    PubgMatchService matches = Mockito.mock(PubgMatchService.class);
    KillCompetitionPubgAggregator aggregator = new KillCompetitionPubgAggregator(players, matches);

    @Test
    void deduplicatesMatchesUsesStartTimeWindowAndAggregatesParticipantKills() {
        var inputs = List.of(
                new KillCompetitionPubgAggregator.PlayerInput(1L, "apple"),
                new KillCompetitionPubgAggregator.PlayerInput(2L, "fox"));
        when(players.findByAccountIdsFresh("kakao", List.of("apple", "fox"))).thenReturn(List.of(
                new PubgPlayer("apple", "Apple", List.of("before", "shared", "at-end")),
                new PubgPlayer("fox", "Fox", List.of("shared", "at-end"))));
        when(matches.findUniqueMatchesFresh(eq("kakao"), argThat(ids -> ids.size() == 3))).thenReturn(Map.of(
                "before", match("before", "2026-09-15T11:59:59Z", 99, 99),
                "shared", match("shared", "2026-09-15T12:30:00Z", 7, 5),
                "at-end", match("at-end", "2026-09-15T13:00:00Z", 88, 88)));

        KillCompetitionKillSnapshot result = aggregator.aggregate("kakao",
                Instant.parse("2026-09-15T12:00:00Z"), Instant.parse("2026-09-15T13:00:00Z"), inputs);

        assertThat(result.totals().get(1L)).isEqualTo(new KillCompetitionKillSnapshot.PlayerTotal(7, 1));
        assertThat(result.totals().get(2L)).isEqualTo(new KillCompetitionKillSnapshot.PlayerTotal(5, 1));
        assertThat(result.matchKills()).extracting(KillCompetitionKillSnapshot.MatchKill::matchId)
                .containsExactlyInAnyOrder("shared", "shared");
        verify(matches).findUniqueMatchesFresh(eq("kakao"), argThat(ids -> new HashSet<>(ids).size() == 3));
    }

    @Test
    void missingParticipantAccountAbortsTheWholeCalculation() {
        when(players.findByAccountIdsFresh(anyString(), anyList())).thenReturn(List.of());
        assertThatThrownBy(() -> aggregator.aggregate("steam", Instant.EPOCH, Instant.now(),
                List.of(new KillCompetitionPubgAggregator.PlayerInput(1L, "missing"))))
                .isInstanceOf(PubgApiException.class);
        verifyNoInteractions(matches);
    }

    private PubgMatch match(String id, String startedAt, int appleKills, int foxKills) {
        return new PubgMatch(id, Instant.parse(startedAt), "squad", List.of(new PubgTeam(List.of(
                new PubgParticipant("apple", "Apple", appleKills),
                new PubgParticipant("fox", "Fox", foxKills)))));
    }
}
