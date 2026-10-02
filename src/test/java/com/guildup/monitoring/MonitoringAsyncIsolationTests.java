package com.guildup.monitoring;

import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.repository.MonitoringEventRepository;
import com.guildup.monitoring.service.*;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MonitoringAsyncIsolationTests {
    @Test void deploymentShutdownDrainsAlreadyQueuedImportantEvents() throws Exception {
        var executor = new com.guildup.monitoring.config.MonitoringEventExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new java.util.concurrent.atomic.AtomicInteger();
        executor.execute(() -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            completed.incrementAndGet();
        });
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        executor.execute(completed::incrementAndGet);
        try (var caller = Executors.newSingleThreadExecutor()) {
            var shutdown = caller.submit(executor::stopWriter);
            release.countDown();
            shutdown.get(2, TimeUnit.SECONDS);
            assertThat(completed.get()).isEqualTo(2);
            assertThat(executor.isTerminated()).isTrue();
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void blockedOrFullWriterDoesNotWaitOrAffectBusinessFlow() throws Exception {
        var writer = mock(MonitoringEventWriter.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> { entered.countDown(); release.await(); throw new IllegalStateException("DB unavailable"); })
                .when(writer).write(any(), any(), any(), anyString(), any(), any(), any(), anyMap(), any());
        var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        var events = mock(MonitoringEventRepository.class);
        var service = new MonitoringEventService(writer, events, new SafeMonitoringDataSanitizer(), Clock.systemUTC(), executor, true);
        try {
            record(service);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var caller = Executors.newSingleThreadExecutor();
            try {
                caller.submit(() -> { record(service); record(service); return "business-success"; })
                        .get(1, TimeUnit.SECONDS);
                assertThat(executor.getQueue()).hasSize(1);
                assertThat(service.wasRecordedRecently(MonitoringEventCode.UNEXPECTED_EXCEPTION, "test-job", java.time.Duration.ofMinutes(1))).isTrue();
                verifyNoInteractions(events);
            } finally { caller.shutdownNow(); }
        } finally { release.countDown(); executor.shutdownNow(); }
    }
    private void record(MonitoringEventService service) {
        service.recordError(MonitoringCategory.SYSTEM, MonitoringEventCode.UNEXPECTED_EXCEPTION,
                "Job failed", null, null, "test-job", Map.of("jobName", "test"));
    }
}
