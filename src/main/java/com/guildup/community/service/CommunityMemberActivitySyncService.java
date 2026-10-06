package com.guildup.community.service;

import com.guildup.community.dto.CommunityMemberActivityListResponse;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.monitoring.logging.LogContext;
import com.guildup.monitoring.logging.FailureLogContext;
import com.guildup.pubg.exception.PubgApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Instant;

@Service
public class CommunityMemberActivitySyncService {
    private static final Logger log = LoggerFactory.getLogger(CommunityMemberActivitySyncService.class);

    private final CommunityActivitySyncCoordinator coordinator;
    private final CommunityMemberActivitySyncWorker worker;
    private final CommunityMemberActivityService activityService;
    private final MonitoringEventService monitoring;
    private final TaskExecutor executor;

    public CommunityMemberActivitySyncService(
            CommunityActivitySyncCoordinator coordinator,
            CommunityMemberActivitySyncWorker worker,
            CommunityMemberActivityService activityService,
            MonitoringEventService monitoring,
            @Qualifier("communityActivitySyncExecutor") TaskExecutor executor
    ) {
        this.coordinator = coordinator;
        this.worker = worker;
        this.activityService = activityService;
        this.monitoring = monitoring;
        this.executor = executor;
    }

    public CommunityMemberActivityListResponse sync(Long userId, Long communityId, Long communityGameId) {
        Instant attemptedAt = coordinator.begin(userId, communityId, communityGameId);
        long started = System.nanoTime();
        CommunityMemberActivityListResponse accepted;
        try {
            // 작업을 제출하기 전에 DTO를 완성한다. 빠른 worker도 접수 응답을 SUCCESS로 바꾸지 않는다.
            accepted = activityService.getActivities(userId, communityId, communityGameId);
        } catch (RuntimeException exception) {
            handleFailure(userId, communityId, communityGameId, attemptedAt, started, exception);
            throw exception;
        }
        try {
            executor.execute(() -> run(userId, communityId, communityGameId, attemptedAt));
        } catch (RuntimeException rejection) {
            handleFailure(userId, communityId, communityGameId, attemptedAt, started, rejection);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "활동 조회 작업을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.", rejection);
        }
        return accepted;
    }

    private void run(Long userId, Long communityId, Long communityGameId, Instant attemptedAt) {
        long started = System.nanoTime();
        try (var ignored = LogContext.scope(Map.of("communityId", communityId, "communityGameId", communityGameId,
                "userId", userId, "syncAttemptAt", attemptedAt))) {
            try {
                worker.synchronize(communityGameId, attemptedAt);
            } catch (RuntimeException | Error exception) {
                handleFailure(userId, communityId, communityGameId, attemptedAt, started, exception);
                if (exception instanceof Error error) throw error;
            }
        }
    }

    private void handleFailure(Long userId, Long communityId, Long communityGameId, Instant attemptedAt,
                               long started, Throwable exception) {
        recordFailure(userId, communityId, communityGameId, started, exception);
        if (!FailureLogContext.isLogged(exception)) {
            log.error("Community activity task failed. communityId={}, communityGameId={}, attemptAt={}",
                    communityId, communityGameId, attemptedAt, exception);
            FailureLogContext.markLogged(exception);
        }
        try {
            coordinator.fail(communityGameId, attemptedAt);
        } catch (RuntimeException stateUpdateFailure) {
            // 실패 상태 저장 장애가 원래 예외를 덮어쓰지 않게 한다.
            if (stateUpdateFailure != exception) exception.addSuppressed(stateUpdateFailure);
            log.error("Community activity failure state could not be saved. communityId={}, communityGameId={}, userId={}",
                    communityId, communityGameId, userId, stateUpdateFailure);
            monitoring.recordError(MonitoringCategory.DATABASE, MonitoringEventCode.DATABASE_ERROR,
                    "Community activity failure state update failed", communityId, userId,
                    "communityGameId=" + communityGameId, Map.of(
                            "stage", "FAILURE_STATE_SAVE", "communityGameId", communityGameId,
                            "exceptionClass", stateUpdateFailure.getClass().getSimpleName()));
        }
    }

    private void recordFailure(Long userId, Long communityId, Long communityGameId,
                               long started, Throwable exception) {
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
