package com.guildup.community.dto;

/** 한 Discord 멤버에게 규칙을 적용한 미리보기 결과다. */
public record GameNicknameMemberPreviewResponse(
        String discordUserId,
        String discordUsername,
        String discordDisplayName,
        String extractedGameNickname,
        GameNicknameExtractionStatus status
) {
}
