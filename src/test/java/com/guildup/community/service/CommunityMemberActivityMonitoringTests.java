package com.guildup.community.service;

import com.guildup.monitoring.repository.MonitoringEventRepository;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.monitoring.service.MonitoringEventWriter;
import com.guildup.monitoring.service.SafeMonitoringDataSanitizer;
import com.guildup.pubg.exception.PubgApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommunityMemberActivityMonitoringTests {
    private static final Instant ATTEMPT = Instant.parse("2026-10-07T01:00:00Z");
    private final CommunityActivitySyncCoordinator coordinator = mock(CommunityActivitySyncCoordinator.class);
    private final CommunityMemberActivitySyncWorker worker = mock(CommunityMemberActivitySyncWorker.class);
    private final CommunityMemberActivityService activities = mock(CommunityMemberActivityService.class);
    private final MonitoringEventWriter writer = mock(MonitoringEventWriter.class);
    private final TaskExecutor executor = mock(TaskExecutor.class);
    private final List<Runnable> tasks = new ArrayList<>();
    private final MonitoringEventService monitoring = new MonitoringEventService(writer,
            mock(MonitoringEventRepository.class), new SafeMonitoringDataSanitizer(), Clock.systemUTC());
    private final CommunityMemberActivitySyncService service = new CommunityMemberActivitySyncService(
            coordinator, worker, activities, monitoring, executor);

    @BeforeEach
    void setUp() {
        when(coordinator.begin(4L, 12L, 31L)).thenReturn(ATTEMPT);
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; }).when(executor).execute(any());
    }

    @Test
    void monitoringDatabaseFailureKeepsOriginalFailureAndSyncStateUpdate() {
        var original = new PubgApiException("upstream unavailable", null, 503, false);
        doThrow(original).when(worker).synchronize(31L, ATTEMPT);
        doThrow(new IllegalStateException("monitoring database unavailable")).when(writer)
                .write(any(), any(), any(), anyString(), any(), any(), anyString(), anyMap(), any());

        service.sync(4L, 12L, 31L);
        verifyNoInteractions(worker);
        tasks.getFirst().run();
        verify(coordinator).fail(31L, ATTEMPT);
        verify(writer).write(any(), any(), any(), anyString(), eq(12L), eq(4L),
                eq("communityGameId=31"), anyMap(), any());
        verify(activities).getActivities(4L, 12L, 31L);
    }

    @Test
    void cooldownRejectionDoesNotRecordAnOperationalFailureOrCallPubg() {
        var rejection = new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "조회 대기시간");
        when(coordinator.begin(4L, 12L, 31L)).thenThrow(rejection);
        assertThatThrownBy(() -> service.sync(4L, 12L, 31L)).isSameAs(rejection);
        verifyNoInteractions(worker, writer, activities, executor);
    }

    @Test
    void configurationRejectionDoesNotRecordAnOperationalFailure() {
        var rejection = new ResponseStatusException(HttpStatus.BAD_REQUEST, "활동 규칙 설정 필요");
        doThrow(rejection).when(worker).synchronize(31L, ATTEMPT);
        service.sync(4L, 12L, 31L);
        tasks.getFirst().run();
        verify(coordinator).fail(31L, ATTEMPT);
        verifyNoInteractions(writer);
    }

    @Test
    void failureStateDatabaseErrorDoesNotReplaceOriginalFailure() {
        var original = new PubgApiException("upstream unavailable", null, 503, false);
        var stateFailure = new IllegalStateException("state database unavailable");
        doThrow(original).when(worker).synchronize(31L, ATTEMPT);
        doThrow(stateFailure).when(coordinator).fail(31L, ATTEMPT);

        service.sync(4L, 12L, 31L);
        tasks.getFirst().run();
        assertThat(original.getSuppressed()).containsExactly(stateFailure);
        verify(coordinator).fail(31L, ATTEMPT);
    }

    @Test
    void executorRejectionRestoresFailedStateAndReturns503() {
        doThrow(new TaskRejectedException("executor full")).when(executor).execute(any());
        assertThatThrownBy(() -> service.sync(4L, 12L, 31L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(coordinator).fail(31L, ATTEMPT);
        verifyNoInteractions(worker);
    }

    @Test
    void acceptedResponseReadFailureRestoresFailedStateWithoutStartingWorker() {
        var original = new IllegalStateException("list unavailable");
        when(activities.getActivities(4L, 12L, 31L)).thenThrow(original);
        assertThatThrownBy(() -> service.sync(4L, 12L, 31L)).isSameAs(original);
        verify(coordinator).fail(31L, ATTEMPT);
        verifyNoInteractions(worker, executor);
    }
}
