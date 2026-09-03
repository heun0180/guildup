package com.guildup.community.dto;

public record CommunityCreateRequest(
        String name,
        String discordGuildId
) {
}
