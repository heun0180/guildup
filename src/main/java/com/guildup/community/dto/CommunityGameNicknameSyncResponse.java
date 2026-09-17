package com.guildup.community.dto;

/** 저장된 닉네임 규칙을 PUBG 계정과 대조해 클랜원별로 반영한 결과다. */
public record CommunityGameNicknameSyncResponse(
        int totalMembers,
        int synchronizedMembers,
        int createdAccounts,
        int updatedAccounts,
        int failedMembers
) {
}
