package com.guildup.community.dto;

/** 커뮤니티 게임 활동 규칙 변경 요청이다. */
public record CommunityGameActivityRuleRequest(
        Integer activityPeriodDays,
        Integer minimumClanMembersInRoster
) {
}
