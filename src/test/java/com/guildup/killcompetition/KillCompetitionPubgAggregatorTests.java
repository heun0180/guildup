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

    @Test
    void playerFailureContextPreservesUpstreamRateLimitClassificationAndCallCount() {
        var failure = new PubgApiException(com.guildup.pubg.exception.PubgApiErrorCode.PUBG_RATE_LIMITED,
                "요청이 많습니다.", new IllegalStateException("rate limited"), 429, false);
        when(players.findByAccountIdsFresh(anyString(), anyList())).thenThrow(failure);

        assertThatThrownBy(() -> aggregator.aggregate("steam", Instant.EPOCH, Instant.now(),
                List.of(new KillCompetitionPubgAggregator.PlayerInput(1L, "account-a"))))
                .isInstanceOfSatisfying(PubgApiException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(failure.getErrorCode());
                    assertThat(error.getUpstreamStatus()).isEqualTo(429);
                    assertThat(error.isRetryable()).isFalse();
                    assertThat(error.getCause()).hasMessageContaining("stage=PLAYER_FETCH");
                    assertThat(error.getCause().getCause()).isSameAs(failure);
                });
        verify(players).findByAccountIdsFresh("steam", List.of("account-a"));
        verifyNoInteractions(matches);
    }

    @Test
    void appliesEachParticipantsEligibleFromWithoutFetchingMatchTwice() {
        var inputs = List.of(
                new KillCompetitionPubgAggregator.PlayerInput(1L, "apple", Instant.parse("2026-09-15T12:00:00Z")),
                new KillCompetitionPubgAggregator.PlayerInput(2L, "fox", Instant.parse("2026-09-15T12:40:00Z")));
        when(players.findByAccountIdsFresh("kakao", List.of("apple", "fox"))).thenReturn(List.of(
                new PubgPlayer("apple", "Apple", List.of("early", "late")),
                new PubgPlayer("fox", "Fox", List.of("early", "late"))));
        when(matches.findUniqueMatchesFresh(eq("kakao"), anyCollection())).thenReturn(Map.of(
                "early", match("early", "2026-09-15T12:20:00Z", 2, 8),
                "late", match("late", "2026-09-15T12:50:00Z", 3, 5)));

        var result = aggregator.aggregate("kakao", Instant.parse("2026-09-15T12:00:00Z"),
                Instant.parse("2026-09-15T13:00:00Z"), inputs);

        assertThat(result.totals().get(1L)).isEqualTo(new KillCompetitionKillSnapshot.PlayerTotal(5, 2));
        assertThat(result.totals().get(2L)).isEqualTo(new KillCompetitionKillSnapshot.PlayerTotal(5, 1));
        verify(matches, times(1)).findUniqueMatchesFresh(eq("kakao"), anyCollection());
    }

    @Test
    void carriesExistingWinPlaceIntoEachMatchResultWithoutAnotherApiCall() {
        var input = new KillCompetitionPubgAggregator.PlayerInput(1L, "apple");
        when(players.findByAccountIdsFresh("kakao", List.of("apple")))
                .thenReturn(List.of(new PubgPlayer("apple", "Apple", List.of("shared"))));
        PubgParticipant apple = new PubgParticipant("apple", "Apple", 5, 0, 0, 0, 0, 0,
                0, 0, 0, 2, 0, 0, 0, 0, 0);
        when(matches.findUniqueMatchesFresh("kakao", Set.of("shared"))).thenReturn(Map.of(
                "shared", new PubgMatch("shared", Instant.parse("2026-09-15T12:30:00Z"),
                        "solo", List.of(new PubgTeam(List.of(apple))))));

        var result = aggregator.aggregate("kakao", Instant.parse("2026-09-15T12:00:00Z"),
                Instant.parse("2026-09-15T13:00:00Z"), List.of(input));

        assertThat(result.matchKills()).singleElement().satisfies(row -> {
            assertThat(row.kills()).isEqualTo(5);
            assertThat(row.placement()).isEqualTo(2);
        });
        verify(matches, times(1)).findUniqueMatchesFresh("kakao", Set.of("shared"));
    }

    private PubgMatch match(String id, String startedAt, int appleKills, int foxKills) {
        return new PubgMatch(id, Instant.parse(startedAt), "squad", List.of(new PubgTeam(List.of(
                new PubgParticipant("apple", "Apple", appleKills),
                new PubgParticipant("fox", "Fox", foxKills)))));
    }
}
