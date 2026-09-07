package com.guildup.community.activity;

import java.time.Instant;
import java.util.List;

/** 한 경기에서 조회 대상과 같은 팀이었던 GuildUp 클랜원 판정 결과다. */
public record ClanActivityMatch(
        String matchId,
        Instant playedAt,
        String gameMode,
        int clanMemberCountInTeam,
        List<String> clanMembersInTeam,
        List<ClanActivityPlayer> playersInTeam,
        boolean activityRecognized
) {
    public ClanActivityMatch {
        clanMembersInTeam = List.copyOf(clanMembersInTeam);
        playersInTeam = List.copyOf(playersInTeam);
    }
}
