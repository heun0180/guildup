package com.guildup.discord.oauth.dto;

/**
 * 봇 설치 시작 결과다.
 * 이미 설치된 경우 연결 결과를, 미설치된 경우 Discord 초대 URL과 설치 확인용 토큰을 담는다.
 */
public record DiscordBotInstallStartResponse(
        boolean alreadyInstalled,
        String authorizationUrl,
        String installToken,
        Long communityId,
        String guildName
) {
}
