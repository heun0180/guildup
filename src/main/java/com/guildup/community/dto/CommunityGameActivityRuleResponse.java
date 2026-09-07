package com.guildup.community.dto;

import com.guildup.community.domain.CommunityGameActivityRule;

import java.time.Instant;

/** 저장된 커뮤니티 게임 활동 규칙이다. */
public record CommunityGameActivityRuleResponse(
        String gameType,
        String gameName,
        int activityPeriodDays,
        int minimumClanMembersInRoster,
        Instant updatedAt
) {
    public static CommunityGameActivityRuleResponse from(CommunityGameActivityRule rule) {
        var gameType = rule.getCommunityGame().getGameType();
        return new CommunityGameActivityRuleResponse(
                gameType.name(),
                gameType.getDisplayName(),
                rule.getActivityPeriodDays(),
                rule.getMinimumClanMembersInRoster(),
                rule.getUpdatedAt()
        );
    }
}
