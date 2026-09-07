package com.guildup.community.dto;

import com.guildup.community.domain.CommunityUserRole;
import com.guildup.community.domain.GameType;

import java.time.Instant;

public record CommunityDashboardResponse(
        Long id, String name, CommunityUserRole role, boolean discordConnected,
        String discordGuildId, String discordGuildName, Instant lastMemberSyncedAt,
        GameType gameType, String gameName
) {}
