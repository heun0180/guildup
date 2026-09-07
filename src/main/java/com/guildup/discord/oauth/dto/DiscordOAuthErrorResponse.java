package com.guildup.discord.oauth.dto;

/** OAuth 관련 오류를 HTTP 상태 코드와 메시지 형태로 반환하는 공통 응답이다. */
public record DiscordOAuthErrorResponse(
        int status,
        String message
) {
}
