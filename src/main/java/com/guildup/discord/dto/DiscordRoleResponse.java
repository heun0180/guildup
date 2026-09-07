package com.guildup.discord.dto;

/** Discord 역할 선택 화면에 필요한 역할 ID와 이름을 담는 응답이다. */
public record DiscordRoleResponse(
        String id,
        String name
) {
}
