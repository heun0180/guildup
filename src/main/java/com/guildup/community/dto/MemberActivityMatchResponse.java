package com.guildup.community.dto;

import com.guildup.community.domain.CommunityMemberActivityMatch;

import java.time.Instant;
import java.util.List;

public record MemberActivityMatchResponse(
        String matchId,
        Instant playedAt,
        String gameMode,
        int clanMemberCountInTeam,
        List<String> clanMembersInTeam,
        List<MemberActivityPlayerResponse> playersInTeam,
        boolean activityRecognized
) {
    public static MemberActivityMatchResponse from(
            CommunityMemberActivityMatch match,
            String targetAccountId
    ) {
        List<MemberActivityPlayerResponse> players = match.getPlayers().stream()
                .map(MemberActivityPlayerResponse::from)
                .toList();
        return new MemberActivityMatchResponse(
                match.getMatchId(), match.getPlayedAt(), match.getGameMode(),
                match.getClanMemberCountInTeam(),
                players.stream()
                        .filter(MemberActivityPlayerResponse::clanMember)
                        .filter(player -> !java.util.Objects.equals(
                                player.pubgAccountId(), targetAccountId
                        ))
                        .map(MemberActivityPlayerResponse::pubgNickname)
                        .toList(),
                players,
                match.isActivityRecognized()
        );
    }
}
