package com.guildup.discord.oauth.dto;

/** 로그인 사용자가 소유하거나 관리 권한을 가진 Discord 서버를 화면에 전달하는 DTO다. */
public record DiscordManageableGuildResponse(
        String id,
        String name,
        String iconUrl,
        boolean owner
) {
}
