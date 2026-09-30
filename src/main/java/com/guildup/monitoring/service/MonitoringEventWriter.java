package com.guildup.monitoring.service;

import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.repository.MonitoringEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Component
public class MonitoringEventWriter {
    private final MonitoringEventRepository events;

    public MonitoringEventWriter(MonitoringEventRepository events) { this.events = events; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(MonitoringSeverity severity, MonitoringCategory category, MonitoringEventCode eventCode,
                      String message, Long communityId, Long userId, String referenceId,
                      Map<String, Object> metadata, Instant occurredAt) {
        events.save(new MonitoringEvent(severity, category, eventCode, message, communityId, userId,
                referenceId, metadata, occurredAt));
    }
}
