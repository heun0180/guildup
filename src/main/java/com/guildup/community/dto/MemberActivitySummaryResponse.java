package com.guildup.community.dto;

import com.guildup.community.activity.ClanActivityStatus;

import java.time.Instant;

public record MemberActivitySummaryResponse(
        Long memberId,
        String discordNickname,
        String pubgAccountId,
        String gameNickname,
        ClanActivityStatus status,
        Instant lastPubgMatchAt,
        Instant lastClanActivityAt,
        Instant synchronizedAt
) {
}
