package com.guildup.discord.oauth.client.dto;

/**
 * Discord의 {@code /users/@me/guilds} 응답 중 서버 한 개를 표현한다.
 * permissions는 문자열로 전달되는 Discord 권한 비트 필드다.
 */
public record DiscordApiGuild(
        String id,
        String name,
        String icon,
        boolean owner,
        String permissions
) {
}
