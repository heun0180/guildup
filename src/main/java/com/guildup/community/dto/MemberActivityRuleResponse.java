package com.guildup.community.dto;

import com.guildup.community.domain.CommunityGameActivityRule;

public record MemberActivityRuleResponse(
        int activityPeriodDays,
        int minimumClanMembersInRoster
) {
    public static MemberActivityRuleResponse from(CommunityGameActivityRule rule) {
        return new MemberActivityRuleResponse(
                rule.getActivityPeriodDays(), rule.getMinimumClanMembersInRoster()
        );
    }
}
