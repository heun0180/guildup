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
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import com.guildup.community.domain.GameType;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class MonitoringQueryService {
    public enum EventGroup { ALL, ERROR, ACTIVITY, PUBG_API }
    public enum ActivityResult { SUCCESS, FAILED }
    private static final List<MonitoringEventCode> ACTIVITY_CODES = Arrays.stream(MonitoringEventCode.values())
            .filter(code -> code.name().startsWith("ACTIVITY_SYNC_") || code == MonitoringEventCode.COMMUNITY_ACTIVITY_SYNC_FAILED).toList();
    private final MonitoringEventRepository events;
    private final CommunityRepository communities;

    public MonitoringQueryService(MonitoringEventRepository events, CommunityRepository communities) {
        this.events = events;
        this.communities = communities;
    }

    public MonitoringResponses.Page<MonitoringResponses.Event> events(
            MonitoringSeverity severity, MonitoringCategory category, MonitoringEventCode eventCode,
            Long communityId, Instant from, Instant to, int page, int size,
            EventGroup group, GameType gameType, ActivityResult syncStatus, String syncId,
            Long minDurationMs, Sort.Direction order) {
        if (syncId != null && !syncId.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바른 syncId를 입력해 주세요.");
        if (from != null && to != null && from.isAfter(to))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "시작일은 종료일보다 늦을 수 없습니다.");
        if (minDurationMs != null && minDurationMs < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "처리시간은 0 이상이어야 합니다.");
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        Specification<MonitoringEvent> spec = (root, query, cb) -> cb.conjunction();
        if (group == EventGroup.ACTIVITY) spec = spec.and((root, query, cb) -> root.get("eventCode").in(ACTIVITY_CODES));
        if (group == EventGroup.ERROR) spec = spec.and((root, query, cb) -> cb.equal(root.get("severity"), MonitoringSeverity.ERROR));
        if (group == EventGroup.PUBG_API) spec = spec.and((root, query, cb) -> cb.equal(root.get("category"), MonitoringCategory.PUBG_API));
        if (gameType != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("activityGameType"), gameType.name()));
        if (syncStatus != null) spec = spec.and((root, query, cb) -> syncStatus == ActivityResult.SUCCESS
                ? cb.equal(root.get("eventCode"), MonitoringEventCode.ACTIVITY_SYNC_COMPLETED)
                : root.get("eventCode").in(MonitoringEventCode.ACTIVITY_SYNC_FAILED, MonitoringEventCode.COMMUNITY_ACTIVITY_SYNC_FAILED));
        if (syncId != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("referenceId"), syncId.toLowerCase(java.util.Locale.ROOT)));
        if (minDurationMs != null) spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("durationMs"), minDurationMs));
        if (severity != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("severity"), severity));
        if (category != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("category"), category));
        if (eventCode != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("eventCode"), eventCode));
        if (communityId != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("communityId"), communityId));
        if (from != null) spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), from));
        if (to != null) spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("occurredAt"), to));
        var result = events.findAll(spec, PageRequest.of(safePage, safeSize,
                Sort.by(order == null ? Sort.Direction.DESC : order, "occurredAt", "id")));
        var communityIds = result.stream().map(MonitoringEvent::getCommunityId).filter(id -> id != null).toList();
        Map<Long, String> communityNames = communities.findAllById(communityIds).stream()
                .collect(Collectors.toMap(com.guildup.community.domain.Community::getId,
                        com.guildup.community.domain.Community::getName));
        return new MonitoringResponses.Page<>(result.stream().map(event -> toResponse(event, communityNames, false)).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    public MonitoringResponses.Event event(long id) {
        MonitoringEvent event = events.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "모니터링 이벤트를 찾을 수 없습니다."));
        Map<Long, String> names = event.getCommunityId() == null ? Map.of()
                : communities.findById(event.getCommunityId()).stream().collect(Collectors.toMap(
                        com.guildup.community.domain.Community::getId,
                        com.guildup.community.domain.Community::getName, (left, right) -> left));
        return toResponse(event, names, true);
    }

    private MonitoringResponses.Event toResponse(MonitoringEvent event, Map<Long, String> names, boolean includeTrace) {
        var metadata = new LinkedHashMap<>(event.getMetadata());
        if (!includeTrace) metadata.remove("stackTrace");
        return new MonitoringResponses.Event(event.getId(), event.getSeverity(), event.getCategory(),
                event.getEventCode(), event.getMessage(), event.getCommunityId(), names.get(event.getCommunityId()),
                event.getUserId(), event.getReferenceId(), metadata, event.getOccurredAt());
    }
}
