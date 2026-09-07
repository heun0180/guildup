package com.guildup.discord.oauth.store;

/** 봇 초대부터 설치 확인까지 유지해야 할 커뮤니티와 Discord 서버 정보다. */
public record DiscordBotInstallSession(
        Long communityId,
        String guildId,
        String guildName
) {
}
