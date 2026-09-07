package com.guildup.community.dto;

import com.guildup.community.domain.CommunityUser;
import com.guildup.community.domain.CommunityUserRole;
import com.guildup.community.domain.GameType;

public record MyCommunityResponse(
        Long id,
        String name,
        CommunityUserRole role,
        GameType gameType,
        String gameName
) {
    public static MyCommunityResponse from(CommunityUser membership, GameType gameType) {
        return new MyCommunityResponse(membership.getCommunity().getId(),
                membership.getCommunity().getName(), membership.getRole(), gameType,
                gameType == null ? null : gameType.getDisplayName());
    }
}
