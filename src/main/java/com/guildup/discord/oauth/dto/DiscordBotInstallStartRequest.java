package com.guildup.discord.oauth.dto;

/** OAuth 결과 중 사용자가 실제로 선택한 Discord 서버를 봇 설치 API에 전달한다. */
public record DiscordBotInstallStartRequest(
        String oauthResultId,
        String guildId
) {
}
