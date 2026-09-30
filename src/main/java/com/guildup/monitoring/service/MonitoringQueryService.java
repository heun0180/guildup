package com.guildup.monitoring.service;

import com.guildup.community.repository.CommunityRepository;
import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.dto.MonitoringResponses;
import com.guildup.monitoring.repository.MonitoringEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class MonitoringQueryService {
    private final MonitoringEventRepository events;
    private final CommunityRepository communities;

    public MonitoringQueryService(MonitoringEventRepository events, CommunityRepository communities) {
        this.events = events;
        this.communities = communities;
    }

    public MonitoringResponses.Page<MonitoringResponses.Event> events(
            MonitoringSeverity severity, MonitoringCategory category, MonitoringEventCode eventCode,
            Long communityId, Instant from, Instant to, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        Specification<MonitoringEvent> spec = (root, query, cb) -> cb.conjunction();
        if (severity != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("severity"), severity));
        if (category != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("category"), category));
        if (eventCode != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("eventCode"), eventCode));
        if (communityId != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("communityId"), communityId));
        if (from != null) spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
        if (to != null) spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("occurredAt"), to));
        var result = events.findAll(spec, PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "occurredAt", "id")));
        var communityIds = result.stream().map(MonitoringEvent::getCommunityId).filter(id -> id != null).toList();
        Map<Long, String> communityNames = communities.findAllById(communityIds).stream()
                .collect(Collectors.toMap(com.guildup.community.domain.Community::getId,
                        com.guildup.community.domain.Community::getName));
        return new MonitoringResponses.Page<>(result.stream().map(event -> toResponse(event, communityNames)).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    public MonitoringResponses.Event event(long id) {
        MonitoringEvent event = events.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "모니터링 이벤트를 찾을 수 없습니다."));
        Map<Long, String> names = event.getCommunityId() == null ? Map.of()
                : communities.findById(event.getCommunityId()).stream().collect(Collectors.toMap(
                        com.guildup.community.domain.Community::getId,
                        com.guildup.community.domain.Community::getName, (left, right) -> left));
        return toResponse(event, names);
    }

    private MonitoringResponses.Event toResponse(MonitoringEvent event, Map<Long, String> names) {
        return new MonitoringResponses.Event(event.getId(), event.getSeverity(), event.getCategory(),
                event.getEventCode(), event.getMessage(), event.getCommunityId(), names.get(event.getCommunityId()),
                event.getUserId(), event.getReferenceId(), event.getMetadata(), event.getOccurredAt());
    }
}
