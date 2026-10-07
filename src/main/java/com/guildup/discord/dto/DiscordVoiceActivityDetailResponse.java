package com.guildup.discord.dto;

import java.util.List;

/** 선택한 기간과 겹치는 특정 클랜원의 음성 세션 목록과 기간 내 활동 합계다. */
public record DiscordVoiceActivityDetailResponse(
        Long communityMemberId,
        String nickname,
        String discordUserId,
        long totalSeconds,
        List<DiscordVoiceSessionResponse> sessions
) {
}
