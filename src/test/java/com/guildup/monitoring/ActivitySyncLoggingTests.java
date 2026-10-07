package com.guildup.monitoring;

import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.logging.*;
import com.guildup.monitoring.repository.MonitoringEventRepository;
import com.guildup.monitoring.service.*;
import com.guildup.pubg.exception.PubgApiException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.Instant;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActivitySyncLoggingTests {
    private final MonitoringEventWriter writer = mock(MonitoringEventWriter.class);
    private final MonitoringEventService monitoring = new MonitoringEventService(writer,
            mock(MonitoringEventRepository.class), new SafeMonitoringDataSanitizer(), Clock.systemUTC());

    @Test
    void taskAndCallablePropagateOperationAndRestorePreviousDiagnosticContext() throws Exception {
        Instant attemptedAt = Instant.now();
        var operation = new ActivitySyncLog(monitoring, 1L, 2L, 3L, attemptedAt);
        var previous = MDC.getCopyOfContextMap();
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> MDC.put("requestId", "existing-thread")).get();
            java.util.concurrent.Callable<String> wrapped;
            Runnable runnable;
            try (var ignored = operation.open()) {
                wrapped = LogContext.wrapCallable(() -> {
                    assertThat(ActivitySyncLog.current()).isSameAs(operation);
                    assertThat(MDC.get("communityGameId")).isEqualTo("2");
                    operation.apiAttempt("MATCH", 2);
                    return MDC.get("syncId");
                });
                runnable = LogContext.wrap(() -> assertThat(ActivitySyncLog.current()).isSameAs(operation));
            }
            String expected = ActivitySyncLog.syncId(2L, attemptedAt);
            // The ID survives the originating scope ending, independently of any session.
            String propagated = executor.submit(wrapped).get();
            assertThat(propagated).isEqualTo(expected);
            executor.submit(runnable).get();
            executor.submit(() -> {
                assertThat(ActivitySyncLog.current()).isNull();
                assertThat(MDC.get("syncId")).isNull();
                assertThat(MDC.get("requestId")).isEqualTo("existing-thread");
                MDC.clear();
            }).get();
            assertThat(operation.matchMetrics()).containsEntry("apiCalls", 1L);
        }
        assertThat(MDC.getCopyOfContextMap()).isEqualTo(previous);
    }

    @Test
    void wrappedTimeoutsAndDatabaseFailuresAreClassifiedWithoutLeakingSecrets() {
        var timeout = new RuntimeException("wrapper", new PubgApiException(
                com.guildup.pubg.exception.PubgApiErrorCode.PUBG_TIMEOUT, "apiKey=private-value timeout",
                new org.springframework.web.client.ResourceAccessException("network timeout"), null, false));
        assertThat(ActivitySyncLog.failureDetails(timeout, "PLAYER_FETCH"))
                .containsEntry("pubgApiError", true).containsEntry("timeout", true)
                .containsEntry("databaseError", false).containsEntry("pubgErrorCode", "PUBG_TIMEOUT");
        var database = new RuntimeException("password=private-password failed", new java.sql.SQLException("database"));
        var details = ActivitySyncLog.failureDetails(database, "PREPARATION");
        assertThat(details).containsEntry("databaseError", true).containsEntry("errorType", "DATABASE");
        assertThat(details.toString()).doesNotContain("private-password");
    }

    @Test
    void boundsErrorSamplesAndApiDiagnosticsAndAlwaysRecordsOneTerminalFailure() {
        var operation = new ActivitySyncLog(monitoring, 1L, 2L, 3L, Instant.now());
        try (var ignored = operation.open()) {
            operation.stage("MATCH_FETCH");
            for (int index = 0; index < 3000; index++) operation.matchFailed("match-" + index, new PubgApiException("upstream"));
            for (int index = 0; index < 3000; index++) assertThat(operation.allowApiDiagnostics()).isEqualTo(index < 20);
            operation.stageFailed(new PubgApiException("upstream"));
            var error = new PubgApiException("PUBG_API_KEY=private-key csrf=private-csrf Cookie=session-private");
            operation.failed(error);
            operation.failed(error);
            operation.completed();
        }
        verify(writer, times(5)).write(eq(MonitoringSeverity.WARN), any(), eq(MonitoringEventCode.ACTIVITY_SYNC_MATCH_FETCH_FAILED),
                anyString(), anyLong(), anyLong(), anyString(), anyMap(), any());
        verify(writer, times(1)).write(eq(MonitoringSeverity.ERROR), any(), eq(MonitoringEventCode.ACTIVITY_SYNC_FAILED),
                anyString(), anyLong(), anyLong(), anyString(), argThat(metadata -> {
                    assertThat(metadata.get("failedMatches")).isEqualTo(3000L);
                    assertThat(metadata.get("suppressedApiDiagnostics")).isEqualTo(2980);
                    assertThat(metadata.toString()).doesNotContain("private-key", "private-csrf", "session-private");
                    assertThat(metadata.get("stackTrace").toString()).contains("[REDACTED]");
                    return true;
                }), any());
        verify(writer, never()).write(any(), any(), eq(MonitoringEventCode.ACTIVITY_SYNC_COMPLETED),
                anyString(), any(), any(), any(), anyMap(), any());
    }

}
