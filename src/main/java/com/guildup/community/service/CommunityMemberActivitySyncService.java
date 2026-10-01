package com.guildup.community.service;

import com.guildup.community.dto.CommunityMemberActivityListResponse;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.pubg.exception.PubgApiException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CommunityMemberActivitySyncService {

    private final CommunityActivitySyncCoordinator coordinator;
    private final CommunityMemberActivitySyncWorker worker;
    private final CommunityMemberActivityService activityService;
    private final MonitoringEventService monitoring;

    public CommunityMemberActivitySyncService(
            CommunityActivitySyncCoordinator coordinator,
            CommunityMemberActivitySyncWorker worker,
            CommunityMemberActivityService activityService,
            MonitoringEventService monitoring
    ) {
        this.coordinator = coordinator;
        this.worker = worker;
        this.activityService = activityService;
        this.monitoring = monitoring;
    }

    public CommunityMemberActivityListResponse sync(Long userId, Long communityId, Long communityGameId) {
        coordinator.begin(userId, communityId, communityGameId);
        long started = System.nanoTime();
        try {
            worker.synchronize(communityGameId);
        } catch (RuntimeException exception) {
            recordFailure(userId, communityId, communityGameId, started, exception);
            coordinator.fail(communityGameId);
            throw exception;
        }
        return activityService.getActivities(userId, communityId, communityGameId);
    }

    private void recordFailure(Long userId, Long communityId, Long communityGameId,
                               long started, RuntimeException exception) {
        // 권한, 재조회 대기시간, 설정 오류 등 요청 거절은 운영 장애로 기록하지 않는다.
        if (exception instanceof ResponseStatusException response
                && !(exception instanceof PubgApiException) && response.getStatusCode().is4xxClientError()) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("communityGameId", communityGameId);
        metadata.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
        metadata.put("exceptionClass", exception.getClass().getSimpleName());
        if (exception instanceof ResponseStatusException response) {
            metadata.put("status", response.getStatusCode().value());
        }
        if (exception instanceof PubgApiException pubg) {
            metadata.put("pubgErrorCode", pubg.getErrorCode().name());
            if (pubg.getUpstreamStatus() != null) metadata.put("upstreamStatus", pubg.getUpstreamStatus());
        }
        monitoring.recordError(MonitoringCategory.SYSTEM, MonitoringEventCode.COMMUNITY_ACTIVITY_SYNC_FAILED,
                "클랜원 활동 조회에 실패했습니다.", communityId, userId,
                "communityGameId=" + communityGameId, metadata);
    }
}
