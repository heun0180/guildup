package com.guildup.community.dto;

import java.time.Instant;

/** 화면에 기술 세부사항을 노출하지 않는 현재 닉네임 규칙 응답이다. */
public record GameNicknameRuleResponse(
        boolean configured,
        String gameType,
        String currentDiscordNickname,
        String ruleDescription,
        String sampleDiscordNickname,
        String sampleGameNickname,
        Instant updatedAt
) {
}
