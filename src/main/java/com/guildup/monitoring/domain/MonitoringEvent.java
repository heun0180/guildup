package com.guildup.monitoring.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "monitoring_events", indexes = {
        @Index(name = "idx_monitoring_events_occurred_at", columnList = "occurred_at"),
        @Index(name = "idx_monitoring_events_severity_occurred_at", columnList = "severity,occurred_at"),
        @Index(name = "idx_monitoring_events_category_code_occurred_at", columnList = "category,event_code,occurred_at"),
        @Index(name = "idx_monitoring_events_community_occurred_at", columnList = "community_id,occurred_at")
})
public class MonitoringEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private MonitoringSeverity severity;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private MonitoringCategory category;
    @Enumerated(EnumType.STRING) @Column(name = "event_code", nullable = false, length = 64)
    private MonitoringEventCode eventCode;
    @Column(nullable = false, length = 500)
    private String message;
    @Column(name = "community_id") private Long communityId;
    @Column(name = "user_id") private Long userId;
    @Column(name = "reference_id", length = 160) private String referenceId;
    @JdbcTypeCode(SqlTypes.JSON) @Column
    private Map<String, Object> metadata;
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected MonitoringEvent() {}

    public MonitoringEvent(MonitoringSeverity severity, MonitoringCategory category,
                           MonitoringEventCode eventCode, String message, Long communityId,
                           Long userId, String referenceId, Map<String, Object> metadata, Instant occurredAt) {
        this.severity = severity;
        this.category = category;
        this.eventCode = eventCode;
        this.message = message;
        this.communityId = communityId;
        this.userId = userId;
        this.referenceId = referenceId;
        this.metadata = metadata == null || metadata.isEmpty() ? null : new LinkedHashMap<>(metadata);
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public MonitoringSeverity getSeverity() { return severity; }
    public MonitoringCategory getCategory() { return category; }
    public MonitoringEventCode getEventCode() { return eventCode; }
    public String getMessage() { return message; }
    public Long getCommunityId() { return communityId; }
    public Long getUserId() { return userId; }
    public String getReferenceId() { return referenceId; }
    public Map<String, Object> getMetadata() { return metadata == null ? Map.of() : Map.copyOf(metadata); }
    public Instant getOccurredAt() { return occurredAt; }
}
