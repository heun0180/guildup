package com.guildup.community.dto;

import com.guildup.community.domain.CommunityGameActivitySyncStatus;

import java.time.Instant;

public record CommunityActivitySyncResponse(
        CommunityGameActivitySyncStatus status,
        Instant lastSuccessfulSyncAt,
        Instant lastSyncAttemptAt,
        Instant nextSyncAvailableAt,
        boolean syncAvailable
) {
}
