package com.guildup.community.dto;

import java.util.List;

/** 저장 전 규칙의 전체 Discord 멤버 적용 결과다. */
public record GameNicknameRulePreviewResponse(
        String gameType,
        String currentDiscordNickname,
        String enteredGameNickname,
        String ruleDescription,
        int totalMembers,
        int successfulMembers,
        int failedMembers,
        double successRate,
        List<GameNicknameMemberPreviewResponse> members
) {
}
