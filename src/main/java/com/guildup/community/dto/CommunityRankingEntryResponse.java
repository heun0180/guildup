package com.guildup.community.dto;

public record CommunityRankingEntryResponse(
        int rank,
        Long memberId,
        String nickname,
        int score,
        boolean me
) {}
