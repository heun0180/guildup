package com.guildup.community.dto;

import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;
import com.guildup.community.domain.GameType;
import java.util.List;

public record MyCommunityResponse(
        Long id,
        String name,
        CommunityUserRole role,
        GameType gameType,
        String gameName,
        List<CommunityGameResponse> games
) {
    public static MyCommunityResponse from(CommunityUser membership, List<CommunityGameResponse> games) {
        GameType gameType = games.isEmpty() ? null : games.get(0).gameType();
        return new MyCommunityResponse(membership.getCommunity().getId(),
                membership.getCommunity().getName(), membership.getRole(), gameType,
                gameType == null ? null : gameType.getDisplayName(), games);
    }
}
