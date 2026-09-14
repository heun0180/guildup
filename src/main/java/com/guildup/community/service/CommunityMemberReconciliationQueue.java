package com.guildup.community.service;

import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

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
            executor.execute(() -> reconcileOne(communityId));
            return true;
        } catch (RuntimeException exception) {
            queuedOrRunning.remove(communityId);
            log.error("Discord member reconciliation enqueue failed - communityId: {}", communityId, exception);
            return false;
        }
    }

    private void reconcileOne(Long communityId) {
        Instant startedAt = Instant.now();
        log.info("Discord member reconciliation started - communityId: {}", communityId);
        try {
            var result = syncService.reconcile(communityId);
            log.info("Discord member reconciliation completed - communityId: {}, matched: {}, created: {}, "
                            + "reactivated: {}, left: {}, durationMs: {}",
                    communityId, result.matchedMembers(), result.createdMembers(), result.reactivatedMembers(),
                    result.leftMembers(), java.time.Duration.between(startedAt, Instant.now()).toMillis());
        } catch (RuntimeException exception) {
            log.error("Discord member reconciliation failed - communityId: {}", communityId, exception);
        } finally {
            queuedOrRunning.remove(communityId);
        }
    }
}
