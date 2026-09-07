package com.guildup.discord.oauth.dto;

/** Discord 사용자 API 응답을 연결 화면에 필요한 필드만 골라 변환한 DTO다. */
public record DiscordOAuthUserResponse(
        String discordUserId,
        String username,
        String globalName,
        String avatarUrl
) {
}
