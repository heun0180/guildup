package com.guildup.pubg.model;

import java.time.Instant;
import java.util.List;

/** 활동과 콘텐츠 판정에 필요한 경기 요약 및 Telemetry 위치다. */
public record PubgMatch(String matchId, Instant playedAt, String gameMode, String mapName,
                        String matchType, Boolean customMatch, String telemetryUrl, List<PubgTeam> teams) {
    public PubgMatch {
        teams = teams == null ? List.of() : List.copyOf(teams);
    }
    public PubgMatch(String matchId, Instant playedAt, String gameMode, List<PubgTeam> teams) {
        this(matchId, playedAt, gameMode, null, null, null, null, teams);
    }
    public PubgMatch(String matchId, Instant playedAt, String gameMode, String mapName,
                     String telemetryUrl, List<PubgTeam> teams) {
        this(matchId, playedAt, gameMode, mapName, null, null, telemetryUrl, teams);
    }
}
