package com.guildup.discord.dto;

/** Discord 역할별 멤버 화면에 필요한 사용자 정보를 담는 응답이다. */
public record DiscordMemberResponse(
        String id,
        String username,
        String displayName,
        String avatarUrl
) {
}
