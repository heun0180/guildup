package com.guildup.community.dto;

import java.util.List;

public record CommunityRankingsResponse(
        MyCommunityRankingResponse myRanking,
        List<CommunityRankingEntryResponse> rankings
) {}
