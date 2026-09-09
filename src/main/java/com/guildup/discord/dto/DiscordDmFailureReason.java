package com.guildup.discord.dto;

/** 클라이언트에 공개해도 안전한 Discord DM 실패 사유다. */
public enum DiscordDmFailureReason {
    DM_NOT_AVAILABLE,
    USER_NOT_FOUND,
    DISCORD_API_ERROR
}
