package com.guildup.discord.dto;

import java.time.Instant;

/** 최근 14일 클랜원별 Discord 음성 활동 합계다. */
public record DiscordVoiceActivitySummaryResponse(
        Long communityMemberId,
        String nickname,
        String discordUserId,
        long totalSeconds,
        Instant lastJoinedAt,
        boolean currentlyConnected
) {
}
