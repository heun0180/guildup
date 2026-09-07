package com.guildup.community.service;

import com.guildup.community.domain.CommunityGameActivitySync;
import com.guildup.community.domain.CommunityGameActivitySyncStatus;
import com.guildup.community.dto.CommunityActivitySyncResponse;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class CommunityActivitySyncPolicy {

    public static final Duration SUCCESS_INTERVAL = Duration.ofHours(24);
    public static final Duration FAILED_COOLDOWN = Duration.ofMinutes(5);
    public static final Duration STALE_SYNC_TIMEOUT = Duration.ofMinutes(30);

    public CommunityActivitySyncResponse describe(CommunityGameActivitySync sync, Instant now) {
        if (sync == null) {
            return new CommunityActivitySyncResponse(
                    CommunityGameActivitySyncStatus.NEVER_SYNCED,
                    null, null, null, true
            );
        }

        Instant nextAvailableAt = null;
        boolean available = true;
        if (sync.isSyncingAt(now, STALE_SYNC_TIMEOUT)) {
            available = false;
            nextAvailableAt = sync.getLastSyncAttemptAt().plus(STALE_SYNC_TIMEOUT);
        } else {
            Instant successLimit = sync.successfulLimitUntil(SUCCESS_INTERVAL);
            if (successLimit != null && successLimit.isAfter(now)) {
                available = false;
                nextAvailableAt = successLimit;
            } else {
                Instant failedLimit = sync.failedCooldownUntil(FAILED_COOLDOWN);
                if (failedLimit != null && failedLimit.isAfter(now)) {
                    available = false;
                    nextAvailableAt = failedLimit;
                }
            }
        }
        return new CommunityActivitySyncResponse(
                sync.getSyncStatus(),
                sync.getLastSuccessfulSyncAt(),
                sync.getLastSyncAttemptAt(),
                nextAvailableAt,
                available
        );
    }
}
