package com.guildup.community.dto;

import com.guildup.community.domain.DiscordCommunityConnection;

/** 연결된 Discord 서버 소속으로 확인되어 가입할 수 있는 GuildUp Community다. */
public record DiscoverableCommunityResponse(
        Long communityId,
        String communityName,
        String discordGuildName
) {
    public static DiscoverableCommunityResponse from(DiscordCommunityConnection connection) {
        return new DiscoverableCommunityResponse(
                connection.getCommunity().getId(),
                connection.getCommunity().getName(),
                connection.getDiscordGuildName()
        );
    }
}
