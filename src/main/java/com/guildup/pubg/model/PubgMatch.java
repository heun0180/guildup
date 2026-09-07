package com.guildup.pubg.model;

import java.time.Instant;
import java.util.List;

/** 활동 판정에 필요한 PUBG 경기 시각, 모드와 팀 구성이다. */
public record PubgMatch(String matchId, Instant playedAt, String gameMode, List<PubgTeam> teams) {
    public PubgMatch {
        teams = teams == null ? List.of() : List.copyOf(teams);
    }
}
