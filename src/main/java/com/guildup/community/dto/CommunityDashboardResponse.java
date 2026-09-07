package com.guildup.community.dto;

import com.guildup.community.domain.CommunityUserRole;

public record CommunityDashboardResponse(
        Long id, String name, CommunityUserRole role, boolean discordConnected,
        String discordGuildId, String discordGuildName
) {}
