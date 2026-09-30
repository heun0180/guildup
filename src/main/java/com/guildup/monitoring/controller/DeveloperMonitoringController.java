package com.guildup.monitoring.controller;

import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.dto.MonitoringResponses;
import com.guildup.monitoring.service.MonitoringQueryService;
import com.guildup.monitoring.service.MonitoringStatusService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/developer/monitoring")
public class DeveloperMonitoringController {
    private final MonitoringStatusService status;
    private final MonitoringQueryService queries;

    public DeveloperMonitoringController(MonitoringStatusService status, MonitoringQueryService queries) {
        this.status = status;
        this.queries = queries;
    }

    @GetMapping("/summary")
    public MonitoringResponses.Summary summary() { return status.summary(); }

    @GetMapping("/events")
    public MonitoringResponses.Page<MonitoringResponses.Event> events(
            @RequestParam(required = false) MonitoringSeverity severity,
            @RequestParam(required = false) MonitoringCategory category,
            @RequestParam(required = false) MonitoringEventCode eventCode,
            @RequestParam(required = false) Long communityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.events(severity, category, eventCode, communityId, from, to, page, size);
    }

    @GetMapping("/events/{id}")
    public MonitoringResponses.Event event(@PathVariable long id) { return queries.event(id); }
}
