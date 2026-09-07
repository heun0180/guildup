package com.guildup.community.service;

import com.guildup.community.dto.CommunityMemberActivityListResponse;
import org.springframework.stereotype.Service;

@Service
public class CommunityMemberActivitySyncService {

    private final CommunityActivitySyncCoordinator coordinator;
    private final CommunityMemberActivitySyncWorker worker;
    private final CommunityMemberActivityService activityService;

    public CommunityMemberActivitySyncService(
            CommunityActivitySyncCoordinator coordinator,
            CommunityMemberActivitySyncWorker worker,
            CommunityMemberActivityService activityService
    ) {
        this.coordinator = coordinator;
        this.worker = worker;
        this.activityService = activityService;
    }

    public CommunityMemberActivityListResponse sync(Long userId, Long communityId) {
        Long communityGameId = coordinator.begin(userId, communityId);
        try {
            worker.synchronize(communityGameId);
        } catch (RuntimeException exception) {
            coordinator.fail(communityGameId);
            throw exception;
        }
        return activityService.getActivities(userId, communityId);
    }
}
