package com.guildup.community.dto;

import com.guildup.community.activity.ClanActivityStatus;

import java.time.Instant;
import java.util.List;

public record CommunityMemberActivityDetailResponse(
        MemberActivitySummaryResponse member,
        ClanActivityStatus status,
        Instant lastClanActivityAt,
        MemberActivityRuleResponse rule,
        List<MemberActivityMatchResponse> matches
) {
}
