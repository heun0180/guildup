package com.guildup.monitoring;

import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.repository.MonitoringEventRepository;
import com.guildup.monitoring.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonitoringEventServiceTests {
    private final Instant now = Instant.parse("2026-09-30T05:00:00Z");

    @Test
    void removesSecretsBeforeWritingWarnAndErrorEvents() {
        MonitoringEventWriter writer = mock(MonitoringEventWriter.class);
        MonitoringEventService service = new MonitoringEventService(writer,
                mock(MonitoringEventRepository.class), new SafeMonitoringDataSanitizer(),
                Clock.fixed(now, ZoneOffset.UTC));

        service.recordWarn(MonitoringCategory.PUBG_API, MonitoringEventCode.PUBG_API_RETRY,
                "Authorization=Bearer abc.def", 12L, 4L, "MATCH", Map.of(
                        "matchId", "match-1", "PUBG_API_KEY", "private-key",
                        "authorizationHeader", "Bearer token-value", "retryCount", 2));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(writer).write(eq(MonitoringSeverity.WARN), eq(MonitoringCategory.PUBG_API),
                eq(MonitoringEventCode.PUBG_API_RETRY), message.capture(), eq(12L), eq(4L),
                eq("MATCH"), metadata.capture(), eq(now));
        assertThat(message.getValue()).doesNotContain("abc.def").contains("[REDACTED]");
        assertThat(metadata.getValue()).containsEntry("matchId", "match-1").containsEntry("retryCount", 2);
        assertThat(metadata.getValue()).doesNotContainKeys("PUBG_API_KEY", "authorizationHeader");
    }

    @Test
    void monitoringWriteFailureNeverEscapesToBusinessFlow() {
        MonitoringEventWriter writer = mock(MonitoringEventWriter.class);
        doThrow(new IllegalStateException("database unavailable")).when(writer).write(
                any(), any(), any(), anyString(), any(), any(), any(), anyMap(), any());
        MonitoringEventService service = new MonitoringEventService(writer,
                mock(MonitoringEventRepository.class), new SafeMonitoringDataSanitizer(),
                Clock.fixed(now, ZoneOffset.UTC));

        assertThatNoException().isThrownBy(() -> service.recordError(MonitoringCategory.BINGO,
                MonitoringEventCode.BINGO_AGGREGATION_FAILED, "Bingo aggregation failed",
                1L, 2L, "bingoEventId=3", Map.of("bingoEventId", 3L)));
    }
}
