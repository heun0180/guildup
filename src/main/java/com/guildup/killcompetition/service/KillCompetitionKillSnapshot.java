package com.guildup.killcompetition.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record KillCompetitionKillSnapshot(
        Map<Long, PlayerTotal> totals,
        List<MatchKill> matchKills
) {
    public record PlayerTotal(int kills, int matchCount) {}
    public record MatchKill(Long participantId, String matchId, Instant startedAt, int kills) {}
}
