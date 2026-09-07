package com.guildup.discord.oauth.dto;

/** 봇 설치 확인 시 브라우저가 서버에 보내는 임시 설치 토큰이다. */
public record DiscordBotInstallConfirmRequest(
        String installToken
) {
}
