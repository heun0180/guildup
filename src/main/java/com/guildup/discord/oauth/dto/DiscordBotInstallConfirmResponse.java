package com.guildup.discord.oauth.dto;

/** 봇 설치 확인과 커뮤니티 연결 저장이 끝난 뒤 화면에 반환하는 결과다. */
public record DiscordBotInstallConfirmResponse(
        boolean connected,
        Long communityId,
        String guildName
) {
}
