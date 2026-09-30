package com.guildup.monitoring.dto;

import com.guildup.monitoring.domain.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class MonitoringResponses {
    private MonitoringResponses() {}

    public record Summary(Server server, Database database, Last24Hours last24Hours) {}
    public record Server(String status, long uptimeSeconds, Double cpuUsage, long memoryUsed,
                         long memoryMax, long diskUsed, long diskTotal) {}
    public record Database(String status) {}
    public record Last24Hours(long errors, long http5xx, long pubg429, long pubgTimeouts,
                              long pubgFailures, long bingoFailures, long killCompetitionFailures,
                              long discordFailures) {}
    public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}
    public record Event(Long id, MonitoringSeverity severity, MonitoringCategory category,
                        MonitoringEventCode eventCode, String message, Long communityId,
                        String communityName, Long userId, String referenceId,
                        Map<String, Object> metadata, Instant occurredAt) {}
}
