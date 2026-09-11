package com.guildup.discord.dto;

import java.time.Instant;

/** 한 음성 채널 접속 구간의 화면용 정보다. */
public record DiscordVoiceSessionResponse(
        String channelId,
        String channelName,
        Instant joinedAt,
        Instant leftAt,
        long durationSeconds,
        boolean currentlyConnected
) {
}
