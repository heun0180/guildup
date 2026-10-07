package com.guildup.community.service;

import com.guildup.community.dto.CommunityMemberActivityListResponse;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.monitoring.logging.LogContext;
import com.guildup.monitoring.logging.ActivitySyncLog;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.time.Instant;
import java.time.Clock;

@Service
public class CommunityMemberActivitySyncService {
    private static final Logger log = LoggerFactory.getLogger(CommunityMemberActivitySyncService.class);

    private final CommunityActivitySyncCoordinator coordinator;
    private final CommunityMemberActivitySyncWorker worker;
    private final CommunityMemberActivityService activityService;
    private final MonitoringEventService monitoring;
    private final TaskExecutor executor;
    private final Clock clock;

    public CommunityMemberActivitySyncService(
            CommunityActivitySyncCoordinator coordinator,
            CommunityMemberActivitySyncWorker worker,
            CommunityMemberActivityService activityService,
            MonitoringEventService monitoring,
            @Qualifier("communityActivitySyncExecutor") TaskExecutor executor,
            Clock clock
    ) {
        this.coordinator = coordinator;
        this.worker = worker;
        this.activityService = activityService;
        this.monitoring = monitoring;
        this.executor = executor;
        this.clock = clock;
    }

    public CommunityMemberActivityListResponse sync(Long userId, Long communityId, Long communityGameId) {
        Instant attemptedAt = coordinator.begin(userId, communityId, communityGameId);
        var operation = new ActivitySyncLog(monitoring, communityId, communityGameId, userId, attemptedAt, clock);
        try (var ignored = operation.open()) {
            operation.info(MonitoringEventCode.ACTIVITY_SYNC_QUEUED, "인게임 활동 조회 작업을 접수했습니다.", Map.of());
            CommunityMemberActivityListResponse accepted;
            try {
                // 작업을 제출하기 전에 DTO를 완성한다. 빠른 worker도 접수 응답을 SUCCESS로 바꾸지 않는다.
                accepted = activityService.getActivities(userId, communityId, communityGameId);
            } catch (RuntimeException | Error exception) {
                handleFailure(userId, communityId, communityGameId, attemptedAt, operation, exception);
                throw exception;
            }
            try {
                executor.execute(LogContext.wrap(() -> run(userId, communityId, communityGameId, attemptedAt, operation)));
            } catch (RuntimeException rejection) {
                operation.stage("TASK_SUBMISSION");
                handleFailure(userId, communityId, communityGameId, attemptedAt, operation, rejection);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "활동 조회 작업을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.", rejection);
            }
            return accepted;
        }
    }

    private void run(Long userId, Long communityId, Long communityGameId, Instant attemptedAt, ActivitySyncLog operation) {
        try (var ignored = operation.open()) {
            try {
                worker.synchronize(communityGameId, attemptedAt);
            } catch (RuntimeException | Error exception) {
                handleFailure(userId, communityId, communityGameId, attemptedAt, operation, exception);
                if (exception instanceof Error error) throw error;
            }
        }
    }

    private void handleFailure(Long userId, Long communityId, Long communityGameId, Instant attemptedAt,
                               ActivitySyncLog operation, Throwable exception) {
        operation.failed(exception);
        try {
            coordinator.fail(communityGameId, attemptedAt);
        } catch (RuntimeException stateUpdateFailure) {
            // 실패 상태 저장 장애가 원래 예외를 덮어쓰지 않게 한다.
            if (stateUpdateFailure != exception) exception.addSuppressed(stateUpdateFailure);
            log.error("Community activity failure state could not be saved. communityId={}, communityGameId={}, userId={}",
                    communityId, communityGameId, userId, stateUpdateFailure);
            var metadata = ActivitySyncLog.failureDetails(stateUpdateFailure, "FAILURE_STATE_SAVE");
            metadata.put("communityGameId", communityGameId);
            metadata.put("syncId", ActivitySyncLog.syncId(communityGameId, attemptedAt));
            metadata.put("failureStateSaved", false);
            monitoring.recordError(MonitoringCategory.DATABASE, MonitoringEventCode.DATABASE_ERROR,
                    "Community activity failure state update failed", communityId, userId,
                    ActivitySyncLog.syncId(communityGameId, attemptedAt), metadata);
        }
    }

}
