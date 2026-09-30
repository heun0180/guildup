package com.guildup.monitoring.repository;

import com.guildup.monitoring.domain.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface MonitoringEventRepository extends JpaRepository<MonitoringEvent, Long>,
        JpaSpecificationExecutor<MonitoringEvent> {
    long countBySeverityAndOccurredAtGreaterThanEqual(MonitoringSeverity severity, Instant from);
    long countByEventCodeAndOccurredAtGreaterThanEqual(MonitoringEventCode eventCode, Instant from);
    long countByEventCodeInAndOccurredAtGreaterThanEqual(Collection<MonitoringEventCode> eventCodes, Instant from);
    boolean existsByEventCodeAndReferenceIdAndOccurredAtGreaterThanEqual(
            MonitoringEventCode eventCode, String referenceId, Instant from);
    List<MonitoringEvent> findByOccurredAtBeforeOrderByOccurredAtAsc(Instant cutoff, Pageable pageable);
}
