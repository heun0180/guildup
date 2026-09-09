package com.guildup.community.service;

import com.guildup.community.dto.TeamMakerParticipantResponse;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TeamBalanceServiceTests {
    private final TeamBalanceService service = new TeamBalanceService();

    @Test
    void createsFourTeamsForSixteenPlayers() {
        var result = service.balance(players(16), 4, 1L);

        assertThat(result.teams()).hasSize(4);
        assertThat(result.teams()).allSatisfy(team -> assertThat(team.memberCount()).isEqualTo(4));
        assertEveryPlayerAssignedOnce(result, 16);
    }

    @Test
    void distributesTwentyTwoPlayersAsFourFoursAndTwoThrees() {
        var result = service.balance(players(22), 4, 2L);

        assertThat(result.teams()).hasSize(6);
        assertThat(result.teams()).extracting(team -> team.memberCount())
                .containsExactly(4, 4, 4, 4, 3, 3);
        assertEveryPlayerAssignedOnce(result, 22);
    }

    @Test
    void distributesEighteenPlayersWithoutWaitingListAndKeepsSizeDifferenceAtOne() {
        var result = service.balance(players(18), 4, 3L);

        assertThat(result.teams()).extracting(team -> team.memberCount())
                .containsExactly(4, 4, 4, 3, 3);
        int minimum = result.teams().stream().mapToInt(team -> team.memberCount()).min().orElseThrow();
        int maximum = result.teams().stream().mapToInt(team -> team.memberCount()).max().orElseThrow();
        assertThat(maximum - minimum).isLessThanOrEqualTo(1);
        assertEveryPlayerAssignedOnce(result, 18);
    }

    @Test
    void compensatesSmallerTeamsWithMeaningfullyHigherAverageDamage() {
        for (long seed = 1; seed <= 20; seed++) {
            var result = service.balance(players(18), 4, seed);
            assertSmallerTeamsAreCompensated(result, 4, 3);
            assertThat(result.summary().totalDifference()).isLessThan(180);
            assertThat(result.summary().adjustedAverageDifference()).isLessThan(45);
        }
    }

    @Test
    void keepsHeadcountCompensationForTwentyTwoPlayers() {
        for (long seed = 1; seed <= 20; seed++) {
            assertSmallerTeamsAreCompensated(service.balance(players(22), 4, seed), 4, 3);
        }
    }

    private List<TeamMakerParticipantResponse> players(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(index -> new TeamMakerParticipantResponse(
                        (long) index, "Discord " + index, "PUBG" + index,
                        (100.0 + index * 20) * 10, 10L, 100.0 + index * 20
                )).toList();
    }

    private void assertEveryPlayerAssignedOnce(TeamBalanceService.BalanceResult result, int count) {
        List<Long> ids = result.teams().stream().flatMap(team -> team.participants().stream())
                .map(TeamMakerParticipantResponse::memberId).toList();
        assertThat(ids).hasSize(count);
        assertThat(new HashSet<>(ids)).hasSize(count);
    }

    private void assertSmallerTeamsAreCompensated(TeamBalanceService.BalanceResult result,
                                                   int largerSize, int smallerSize) {
        double largeTeamAverage = result.teams().stream().filter(team -> team.memberCount() == largerSize)
                .mapToDouble(team -> team.averageDamage()).average().orElseThrow();
        double smallTeamAverage = result.teams().stream().filter(team -> team.memberCount() == smallerSize)
                .mapToDouble(team -> team.averageDamage()).average().orElseThrow();
        double strongestLargeTeam = result.teams().stream().filter(team -> team.memberCount() == largerSize)
                .mapToDouble(team -> team.averageDamage()).max().orElseThrow();
        double weakestSmallTeam = result.teams().stream().filter(team -> team.memberCount() == smallerSize)
                .mapToDouble(team -> team.averageDamage()).min().orElseThrow();

        assertThat(smallTeamAverage / largeTeamAverage).isGreaterThanOrEqualTo(1.28);
        assertThat(weakestSmallTeam).isGreaterThan(strongestLargeTeam * 1.20);
        assertThat(result.teams()).allSatisfy(team ->
                assertThat(team.adjustedAverageDamage()).isEqualTo(team.totalDamage() / largerSize));
    }
}
