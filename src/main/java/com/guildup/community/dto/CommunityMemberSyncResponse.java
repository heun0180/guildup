package com.guildup.community.dto;

public record CommunityMemberSyncResponse(
        int added,
        int updated,
        int removed,
        long total
) {
}
