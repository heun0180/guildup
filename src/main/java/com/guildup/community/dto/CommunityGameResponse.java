package com.guildup.community.dto;

import com.guildup.community.domain.CommunityGame;
import com.guildup.community.domain.GameCapability;
import com.guildup.community.domain.GameType;

import java.util.Set;

public record CommunityGameResponse(Long communityGameId, GameType gameType, String gameName,
                                    Set<GameCapability> capabilities) {
    public static CommunityGameResponse from(CommunityGame game) {
        return new CommunityGameResponse(game.getId(), game.getGameType(),
                game.getGameType().getDisplayName(), game.getGameType().getCapabilities());
    }
}
