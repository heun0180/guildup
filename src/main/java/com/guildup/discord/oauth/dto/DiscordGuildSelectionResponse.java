package com.guildup.discord.oauth.dto;

/** 선택한 Discord 서버의 기존 GuildUp 연결 및 현재 사용자 참여 상태다. */
public record DiscordGuildSelectionResponse(
        boolean alreadyConnected,
        Long communityId,
        String communityName,
        boolean alreadyMember
) {
    public static DiscordGuildSelectionResponse available() {
        return new DiscordGuildSelectionResponse(false, null, null, false);
    }
}
