package com.guildup.discord.dto;

import java.util.List;

/** 특정 클랜원의 최근 14일 음성 세션 목록이다. */
public record DiscordVoiceActivityDetailResponse(
        Long communityMemberId,
        String nickname,
        String discordUserId,
        long totalSeconds,
        List<DiscordVoiceSessionResponse> sessions
) {
}
