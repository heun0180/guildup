package com.guildup.monitoring.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import java.util.Map;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;

class RealtimeLogBufferTests {
    @Test void duplicateFailureMarkersRemainBoundedEvenWhileFailuresAreStillReferenced() {
        var failures = new java.util.ArrayList<Throwable>();
        for (int i = 0; i < 5000; i++) {
            var failure = new IllegalStateException("failure-" + i);
            failures.add(failure);
            FailureLogContext.markLogged(failure);
        }
        assertThat(failures.stream().filter(FailureLogContext::isLogged).count()).isLessThanOrEqualTo(4096);
        assertThat(FailureLogContext.isLogged(failures.getLast())).isTrue();
        assertThat(FailureLogContext.isLogged(new RuntimeException("wrapper", failures.getLast()))).isTrue();
    }

    private LoggingEvent event(int index) {
        var context = new LoggerContext();
        var event = new LoggingEvent("test", context.getLogger("com.guildup.bingo.Aggregation"), Level.INFO,
                "matchId=" + index, null, null);
        event.setMDCPropertyMap(Map.of("requestId", "req-123456", "userId", "7", "accessToken", "never-retain"));
        return event;
    }

    @Test void boundsRetentionAndReportsMissingAndRestartCursors() {
        var buffer = new RealtimeLogBuffer(500);
        buffer.append(event(0));
        String first = buffer.read(null, 200).entries().getFirst().id();
        for (int i = 1; i <= 700; i++) buffer.append(event(i));
        assertThat(buffer.size()).isEqualTo(500);
        var replay = buffer.read(first, 200);
        assertThat(replay.gap()).isTrue();
        assertThat(replay.entries()).hasSize(200);
        assertThat(replay.entries().getFirst().message()).isEqualTo("matchId=201");
        assertThat(buffer.read(null, 200).entries().getLast().message()).isEqualTo("matchId=700");
        assertThat(buffer.read("old-process:999999", 200).gap()).isTrue();
        var latest = buffer.read(null, 200).entries().getLast();
        assertThat(buffer.read(latest.id(), 200).entries()).isEmpty();
        assertThat(latest.context()).containsEntry("requestId", "req-123456").doesNotContainKey("accessToken");
    }

    @Test void debugNeverEntersBufferAndSecretsAreRemovedBeforeRetention() {
        var buffer = new RealtimeLogBuffer(1000);
        var debug = new LoggingEvent("test", new LoggerContext().getLogger("test"), Level.DEBUG,
                "debug detail", null, null);
        buffer.append(debug);
        assertThat(buffer.size()).isZero();
        var context = new LoggerContext();
        var failure = new IllegalStateException("password='smtp secret' Cookie: JSESSIONID=private-session");
        var event = new LoggingEvent("test", context.getLogger("com.guildup.pubg.Client"), Level.ERROR,
                "Authorization=Bearer private-key\n{\"refresh_token\":\"never-keep\"}\nuser@example.com", failure, null);
        event.setMDCPropertyMap(Map.of());
        buffer.append(event);
        var retained = buffer.read(null, 1).entries().getFirst();
        assertThat(retained.message()).doesNotContain("private-key", "never-keep", "user@example.com");
        assertThat(retained.stackTrace()).contains("IllegalStateException", "\tat ").doesNotContain("smtp secret", "private-session");
        var huge = new LoggingEvent("test", context.getLogger("test"), Level.INFO, "x".repeat(50_000), null, null);
        huge.setMDCPropertyMap(Map.of());
        buffer.append(huge);
        assertThat(buffer.read(null, 2).entries().getLast().message().length()).isLessThan(2030);
    }

    @Test void fileLayoutRedactsStackMessagesAndRetainsFramesOnce() {
        var context = new LoggerContext();
        context.putProperty("test", "test");
        var layout = new PatternLayout();
        layout.setContext(context);
        layout.getInstanceConverterMap().put("safe", SafeLogConverter::new);
        layout.setPattern("%nopex%safe(%level requestId=%X{requestId} %msg%n%ex)");
        layout.start();
        var failure = new IllegalStateException("accessToken=secret-value\nDetail: Key (email)=(row@example.com) already exists.\nSQL [select private_data from users]");
        var event = new LoggingEvent("test", context.getLogger("test"), Level.ERROR,
                "sessionId=private-session api_key=hidden-key", failure, null);
        event.setMDCPropertyMap(Map.of("requestId", "request-123"));
        String encoded = layout.doLayout(event);
        assertThat(encoded).contains("ERROR", "requestId=request-123", "IllegalStateException", "\tat ");
        assertThat(encoded).doesNotContain("secret-value", "row@example.com", "private_data", "private-session", "hidden-key");
        assertThat(encoded).doesNotContain("%nopex", "%PARSER_ERROR");
        assertThat(encoded.split("IllegalStateException", -1)).hasSize(2);
        assertThat(layout.isStarted()).isTrue();
    }

    @Test void propagatesAndRestoresMdcEvenWhenWorkerFails() throws Exception {
        MDC.put("requestId", "caller-123");
        Runnable task = LogContext.wrap(() -> {
            assertThat(MDC.get("requestId")).isEqualTo("caller-123");
            MDC.put("stage", "FACT_SAVE");
            throw new IllegalStateException("test");
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                MDC.put("requestId", "worker-before");
                assertThatThrownBy(task::run).isInstanceOf(IllegalStateException.class);
                assertThat(MDC.get("requestId")).isEqualTo("worker-before");
                assertThat(MDC.get("stage")).isNull();
                MDC.clear();
            }).get();
        } finally { MDC.clear(); }
    }

    @Test void bulkAppendHasBoundedMemoryAndMeasuresLocalCostWithoutExternalCalls() {
        var buffer = new RealtimeLogBuffer(1000);
        var event = event(1);
        for (int i = 0; i < 5000; i++) buffer.append(event);
        long started = System.nanoTime();
        for (int i = 0; i < 20_000; i++) buffer.append(event);
        double micros = (System.nanoTime() - started) / 20_000.0 / 1000;
        assertThat(buffer.size()).isEqualTo(1000);
        assertThat(buffer.read(null, 200).entries()).hasSize(200);
        System.out.printf("LIVE_LOG_BENCH entries=20000 capacity=1000 meanAppendMicros=%.2f%n", micros);
    }
}
