package com.guildup.monitoring.service;

import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.repository.MonitoringEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import com.guildup.monitoring.logging.LogContext;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MonitoringEventService {
    private static final Logger log = LoggerFactory.getLogger(MonitoringEventService.class);
    private final MonitoringEventWriter writer;
    private final MonitoringEventRepository events;
    private final SafeMonitoringDataSanitizer sanitizer;
    private final Clock clock;
    private final Executor executor;
    private final boolean asynchronous;
    private final AtomicLong lastFailureLog = new AtomicLong();
    private final Map<String, java.time.Instant> recent = new LinkedHashMap<>();

    public MonitoringEventService(MonitoringEventWriter writer, MonitoringEventRepository events,
                                  SafeMonitoringDataSanitizer sanitizer, Clock clock) {
        this(writer, events, sanitizer, clock, Runnable::run, false);
    }

    @Autowired
    public MonitoringEventService(MonitoringEventWriter writer, MonitoringEventRepository events,
                                  SafeMonitoringDataSanitizer sanitizer, Clock clock,
                                  @Qualifier("monitoringEventExecutor") Executor executor,
                                  @Value("${monitoring.events.async:true}") boolean asynchronous) {
        this.writer = writer;
        this.events = events;
        this.sanitizer = sanitizer;
        this.clock = clock;
        this.executor = executor;
        this.asynchronous = asynchronous;
    }

    public void recordInfo(MonitoringCategory category, MonitoringEventCode code, String message,
                           Long communityId, Long userId, String referenceId, Map<String, ?> metadata) {
        record(MonitoringSeverity.INFO, category, code, message, communityId, userId, referenceId, metadata);
    }

    public void recordWarn(MonitoringCategory category, MonitoringEventCode code, String message,
                           Long communityId, Long userId, String referenceId, Map<String, ?> metadata) {
        record(MonitoringSeverity.WARN, category, code, message, communityId, userId, referenceId, metadata);
    }

    public void recordError(MonitoringCategory category, MonitoringEventCode code, String message,
                            Long communityId, Long userId, String referenceId, Map<String, ?> metadata) {
        record(MonitoringSeverity.ERROR, category, code, message, communityId, userId, referenceId, metadata);
    }

    public boolean wasRecordedRecently(MonitoringEventCode code, String referenceId, Duration duration) {
        if (asynchronous) {
            synchronized (recent) {
                var last = recent.get(dedupKey(code, referenceId));
                return last != null && !last.isBefore(clock.instant().minus(duration));
            }
        }
        try {
            return events.existsByEventCodeAndReferenceIdAndOccurredAtGreaterThanEqual(
                    code, referenceId, clock.instant().minus(duration));
        } catch (RuntimeException exception) {
            logFailure("Monitoring event deduplication check failed - eventCode=" + code, exception);
            return false;
        }
    }

    private void record(MonitoringSeverity severity, MonitoringCategory category, MonitoringEventCode code,
                        String message, Long communityId, Long userId, String referenceId,
                        Map<String, ?> metadata) {
        try {
            var occurredAt = clock.instant();
            var context = new LinkedHashMap<String, Object>();
            if (metadata != null) {
                int inspected = 0;
                for (var entry : metadata.entrySet()) {
                    if (inspected++ >= 32) break;
                    context.put(entry.getKey(), entry.getValue());
                }
            }
            for (String key : java.util.List.of("requestId", "jobName", "stage", "communityId", "userId", "bingoEventId", "killCompetitionId", "matchId")) {
                if (MDC.get(key) != null) context.putIfAbsent(key, MDC.get(key));
            }
            var safeMetadata = sanitizer.sanitize(context);
            String safeMessage = limited(sanitizer.sanitizeText(message), 500);
            String safeReference = referenceId == null ? null : limited(sanitizer.sanitizeText(referenceId), 160);
            Long eventCommunityId = communityId == null ? contextId("communityId") : communityId;
            Long eventUserId = userId == null ? contextId("userId") : userId;
            Runnable write = LogContext.wrap(() -> {
                try { writer.write(severity, category, code, safeMessage, eventCommunityId, eventUserId, safeReference, safeMetadata, occurredAt); }
                catch (RuntimeException exception) { logFailure("Monitoring event write failed - category=" + category + " eventCode=" + code, exception); }
            });
            if (asynchronous) {
                executor.execute(write);
                synchronized (recent) {
                    recent.put(code + ":" + safeReference, occurredAt);
                    if (recent.size() > 2048) recent.remove(recent.keySet().iterator().next());
                }
            } else write.run();
        } catch (RuntimeException exception) {
            logFailure("Monitoring event dropped - category=" + category + " eventCode=" + code, exception);
        }
    }

    private void logFailure(String message, RuntimeException failure) {
        long now = System.nanoTime();
        long previous = lastFailureLog.get();
        if ((previous == 0 || now - previous >= java.util.concurrent.TimeUnit.MINUTES.toNanos(1))
                && lastFailureLog.compareAndSet(previous, now)) log.warn(message, failure);
    }

    private Long contextId(String key) {
        String value = MDC.get(key);
        if (value == null) return null;
        try { return Long.valueOf(value); }
        catch (NumberFormatException ignored) { return null; }
    }

    private String dedupKey(MonitoringEventCode code, String referenceId) {
        return code + ":" + (referenceId == null ? null : limited(sanitizer.sanitizeText(referenceId), 160));
    }

    private String limited(String value, int maxLength) {
        if (value == null) return null;
        return value.substring(0, Math.min(maxLength, value.length()));
    }
}
