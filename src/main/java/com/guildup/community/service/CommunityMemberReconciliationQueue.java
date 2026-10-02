package com.guildup.community.service;

import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.monitoring.logging.LogContext;
import com.guildup.monitoring.logging.FailureLogContext;

import java.time.Instant;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 연결된 Community ID만 큐에 넣고 제한된 동시성으로 전체 reconciliation을 실행한다. */
@Component
public class CommunityMemberReconciliationQueue {

    private static final Logger log = LoggerFactory.getLogger(CommunityMemberReconciliationQueue.class);

    private final DiscordCommunityConnectionRepository connectionRepository;
    private final CommunityMemberSyncService syncService;
    private final TaskExecutor executor;
    private final Set<Long> queuedOrRunning = ConcurrentHashMap.newKeySet();
    private MonitoringEventService monitoring;

    @Autowired
    void configureMonitoring(MonitoringEventService monitoring) { this.monitoring = monitoring; }

    public CommunityMemberReconciliationQueue(
            DiscordCommunityConnectionRepository connectionRepository,
            CommunityMemberSyncService syncService,
            @Qualifier("discordMemberReconciliationExecutor") TaskExecutor executor
    ) {
        this.connectionRepository = connectionRepository;
        this.syncService = syncService;
        this.executor = executor;
    }

    /** 마지막 전체 확인이 없거나 오래된 Community부터 등록한다. */
    public void enqueueAll() {
        connectionRepository.findAllWithCommunity().stream()
                .sorted(Comparator.comparing(
                        DiscordCommunityConnection::getLastMemberSyncedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ))
                .map(connection -> connection.getCommunity().getId())
                .forEach(this::enqueue);
    }

    public boolean enqueue(Long communityId) {
        if (!queuedOrRunning.add(communityId)) return false;
        try {
            executor.execute(LogContext.wrap(() -> reconcileOne(communityId)));
            return true;
        } catch (RuntimeException exception) {
            queuedOrRunning.remove(communityId);
            log.error("Discord member reconciliation enqueue failed - communityId: {}", communityId, exception);
            recordFailure(communityId, "ENQUEUE", exception);
            return false;
        }
    }

    private void reconcileOne(Long communityId) {
        Instant startedAt = Instant.now();
        log.info("Discord member reconciliation started - communityId: {}", communityId);
        try (var ignored = LogContext.scope(java.util.Map.of("communityId", communityId,
                "jobName", "discordMemberReconciliation"))) {
            var result = syncService.reconcile(communityId);
            log.info("Discord member reconciliation completed - communityId: {}, matched: {}, created: {}, "
                            + "reactivated: {}, left: {}, durationMs: {}",
                    communityId, result.matchedMembers(), result.createdMembers(), result.reactivatedMembers(),
                    result.leftMembers(), java.time.Duration.between(startedAt, Instant.now()).toMillis());
        } catch (RuntimeException exception) {
            if (!FailureLogContext.isLogged(exception)) {
                log.error("Discord member reconciliation failed. jobName=discordMemberReconciliation, communityId={}, elapsedMs={}",
                        communityId, java.time.Duration.between(startedAt, Instant.now()).toMillis(), exception);
                recordFailure(communityId, "RECONCILE", exception);
            } else {
                log.debug("Discord member reconciliation stopped after a recorded failure. communityId={}", communityId);
            }
        } finally {
            queuedOrRunning.remove(communityId);
        }
    }

    private void recordFailure(Long communityId, String stage, RuntimeException exception) {
        if (monitoring != null) monitoring.recordError(MonitoringCategory.DISCORD,
                MonitoringEventCode.DISCORD_MEMBER_LOOKUP_FAILED, "Discord member reconciliation failed",
                communityId, null, "communityId=" + communityId, java.util.Map.of(
                        "jobName", "discordMemberReconciliation", "stage", stage,
                        "exceptionClass", exception.getClass().getSimpleName()));
    }
}
