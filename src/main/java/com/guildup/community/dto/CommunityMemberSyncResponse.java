package com.guildup.community.dto;

import java.time.Instant;

/** Discord 클랜원 동기화 결과 통계다. */
public record CommunityMemberSyncResponse(
        int totalDiscordMembers,
        int matchedMembers,
        int createdMembers,
        int updatedMembers,
        int reactivatedMembers,
        int leftMembers,
        Instant synchronizedAt
) {
}
