package com.guildup.monitoring.service;

import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.repository.MonitoringEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

@Service
public class MonitoringEventService {
    private static final Logger log = LoggerFactory.getLogger(MonitoringEventService.class);
    private final MonitoringEventWriter writer;
    private final MonitoringEventRepository events;
    private final SafeMonitoringDataSanitizer sanitizer;
    private final Clock clock;

    public MonitoringEventService(MonitoringEventWriter writer, MonitoringEventRepository events,
                                  SafeMonitoringDataSanitizer sanitizer, Clock clock) {
        this.writer = writer;
        this.events = events;
        this.sanitizer = sanitizer;
        this.clock = clock;
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
        try {
            return events.existsByEventCodeAndReferenceIdAndOccurredAtGreaterThanEqual(
                    code, referenceId, clock.instant().minus(duration));
        } catch (RuntimeException exception) {
            log.warn("Monitoring event deduplication check failed - eventCode={} exceptionClass={}",
                    code, exception.getClass().getSimpleName());
            return false;
        }
    }

    private void record(MonitoringSeverity severity, MonitoringCategory category, MonitoringEventCode code,
                        String message, Long communityId, Long userId, String referenceId,
                        Map<String, ?> metadata) {
        try {
            writer.write(severity, category, code, limited(sanitizer.sanitizeText(message), 500),
                    communityId, userId,
                    referenceId == null ? null : limited(sanitizer.sanitizeText(referenceId), 160),
                    sanitizer.sanitize(metadata), clock.instant());
        } catch (RuntimeException exception) {
            log.warn("Monitoring event write failed - severity={} category={} eventCode={} exceptionClass={}",
                    severity, category, code, exception.getClass().getSimpleName());
        }
    }

    private String limited(String value, int maxLength) {
        if (value == null) return null;
        return value.substring(0, Math.min(maxLength, value.length()));
    }
}
