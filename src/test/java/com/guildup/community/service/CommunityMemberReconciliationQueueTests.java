package com.guildup.community.service;

import com.guildup.community.domain.Community;
import com.guildup.community.domain.DiscordCommunityConnection;
import com.guildup.community.dto.CommunityMemberSyncResponse;
import com.guildup.community.repository.DiscordCommunityConnectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommunityMemberReconciliationQueueTests {

    private final DiscordCommunityConnectionRepository connections = mock(DiscordCommunityConnectionRepository.class);
    private final CommunityMemberSyncService syncService = mock(CommunityMemberSyncService.class);

    @Test
    void oneCommunityFailureDoesNotStopRemainingQueue() {
        List<DiscordCommunityConnection> connected = List.of(connection(1L), connection(2L), connection(3L));
        when(connections.findAllWithCommunity()).thenReturn(connected);
        when(syncService.reconcile(1L)).thenThrow(new IllegalStateException("Discord unavailable"));
        when(syncService.reconcile(2L)).thenReturn(result());
        when(syncService.reconcile(3L)).thenReturn(result());
        CommunityMemberReconciliationQueue queue = new CommunityMemberReconciliationQueue(
                connections, syncService, Runnable::run
        );

        queue.enqueueAll();

        verify(syncService).reconcile(1L);
        verify(syncService).reconcile(2L);
        verify(syncService).reconcile(3L);
    }

    @Test
    void duplicateCommunityIsNotQueuedWhileFirstTaskIsPending() {
        when(syncService.reconcile(1L)).thenReturn(result());
        List<Runnable> tasks = new ArrayList<>();
        TaskExecutor deferred = tasks::add;
        CommunityMemberReconciliationQueue queue = new CommunityMemberReconciliationQueue(
                connections, syncService, deferred
        );

        assertThat(queue.enqueue(1L)).isTrue();
        assertThat(queue.enqueue(1L)).isFalse();
        assertThat(tasks).hasSize(1);
        tasks.getFirst().run();
        assertThat(queue.enqueue(1L)).isTrue();
    }

    @Test
    void fixedExecutorLimitsConcurrentGuildReconciliations() throws Exception {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.initialize();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch firstTwoStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(8);
        when(syncService.reconcile(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            firstTwoStarted.countDown();
            release.await(2, TimeUnit.SECONDS);
            active.decrementAndGet();
            completed.countDown();
            return result();
        });
        CommunityMemberReconciliationQueue queue = new CommunityMemberReconciliationQueue(
                connections, syncService, executor
        );

        try {
            for (long id = 1; id <= 8; id++) queue.enqueue(id);
            assertThat(firstTwoStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(maximum.get()).isEqualTo(2);
            release.countDown();
            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    private DiscordCommunityConnection connection(Long id) {
        Community community = mock(Community.class);
        when(community.getId()).thenReturn(id);
        return new DiscordCommunityConnection(community, "guild-" + id, "Guild " + id);
    }

    private CommunityMemberSyncResponse result() {
        return new CommunityMemberSyncResponse(0, 0, 0, 0, 0, 0, Instant.now());
    }
}
