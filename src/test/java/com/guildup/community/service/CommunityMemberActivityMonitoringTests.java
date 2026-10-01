package com.guildup.community.service;

import com.guildup.monitoring.repository.MonitoringEventRepository;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.monitoring.service.MonitoringEventWriter;
import com.guildup.monitoring.service.SafeMonitoringDataSanitizer;
import com.guildup.pubg.exception.PubgApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommunityMemberActivityMonitoringTests {
    private final CommunityActivitySyncCoordinator coordinator = mock(CommunityActivitySyncCoordinator.class);
    private final CommunityMemberActivitySyncWorker worker = mock(CommunityMemberActivitySyncWorker.class);
    private final CommunityMemberActivityService activities = mock(CommunityMemberActivityService.class);
    private final MonitoringEventWriter writer = mock(MonitoringEventWriter.class);
    private final MonitoringEventService monitoring = new MonitoringEventService(writer,
            mock(MonitoringEventRepository.class), new SafeMonitoringDataSanitizer(), Clock.systemUTC());
    private final CommunityMemberActivitySyncService service = new CommunityMemberActivitySyncService(
            coordinator, worker, activities, monitoring);

    @Test
    void monitoringDatabaseFailureKeepsOriginalFailureAndSyncStateUpdate() {
        var original = new PubgApiException("upstream unavailable", null, 503, false);
        doThrow(original).when(worker).synchronize(31L);
        doThrow(new IllegalStateException("monitoring database unavailable")).when(writer)
                .write(any(), any(), any(), anyString(), any(), any(), anyString(), anyMap(), any());

        assertThatThrownBy(() -> service.sync(4L, 12L, 31L)).isSameAs(original);
        verify(coordinator).fail(31L);
        verify(writer).write(any(), any(), any(), anyString(), eq(12L), eq(4L),
                eq("communityGameId=31"), anyMap(), any());
        verifyNoInteractions(activities);
    }

    @Test
    void cooldownRejectionDoesNotRecordAnOperationalFailureOrCallPubg() {
        var rejection = new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "조회 대기시간");
        when(coordinator.begin(4L, 12L, 31L)).thenThrow(rejection);
        assertThatThrownBy(() -> service.sync(4L, 12L, 31L)).isSameAs(rejection);
        verifyNoInteractions(worker, writer, activities);
    }

    @Test
    void configurationRejectionDoesNotRecordAnOperationalFailure() {
        var rejection = new ResponseStatusException(HttpStatus.BAD_REQUEST, "활동 규칙 설정 필요");
        doThrow(rejection).when(worker).synchronize(31L);
        assertThatThrownBy(() -> service.sync(4L, 12L, 31L)).isSameAs(rejection);
        verify(coordinator).fail(31L);
        verifyNoInteractions(writer);
    }
}
