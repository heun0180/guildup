package com.guildup.discord.dto;

/** 수신자 한 명에 대한 DM 발송 결과다. */
public record DiscordDmResult(
        String discordUserId,
        String displayName,
        boolean success,
        DiscordDmFailureReason reason
) {
    public static DiscordDmResult success(String userId, String displayName) {
        return new DiscordDmResult(userId, displayName, true, null);
    }

    public static DiscordDmResult failure(
            String userId,
            String displayName,
            DiscordDmFailureReason reason
    ) {
        return new DiscordDmResult(userId, displayName, false, reason);
    }
}
