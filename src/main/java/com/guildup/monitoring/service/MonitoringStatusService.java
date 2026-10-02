package com.guildup.monitoring.service;

import com.guildup.monitoring.domain.*;
import com.guildup.monitoring.dto.MonitoringResponses;
import com.guildup.monitoring.repository.MonitoringEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class MonitoringStatusService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MonitoringStatusService.class);
    private final java.util.concurrent.atomic.AtomicLong lastDiskFailure = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong lastDatabaseFailure = new java.util.concurrent.atomic.AtomicLong();
    private final MonitoringEventRepository events;
    private final MeterRegistry meterRegistry;
    private final DataSource dataSource;
    private final Clock clock;

    public MonitoringStatusService(MonitoringEventRepository events, MeterRegistry meterRegistry,
                                   DataSource dataSource, Clock clock) {
        this.events = events;
        this.meterRegistry = meterRegistry;
        this.dataSource = dataSource;
        this.clock = clock;
    }

    public MonitoringResponses.Summary summary() {
        var from = clock.instant().minus(24, ChronoUnit.HOURS);
        var last24Hours = new MonitoringResponses.Last24Hours(
                events.countBySeverityAndOccurredAtGreaterThanEqual(MonitoringSeverity.ERROR, from),
                events.countByEventCodeAndOccurredAtGreaterThanEqual(MonitoringEventCode.HTTP_5XX, from),
                events.countByEventCodeAndOccurredAtGreaterThanEqual(MonitoringEventCode.PUBG_API_RATE_LIMIT, from),
                events.countByEventCodeAndOccurredAtGreaterThanEqual(MonitoringEventCode.PUBG_API_TIMEOUT, from),
                events.countByEventCodeAndOccurredAtGreaterThanEqual(MonitoringEventCode.PUBG_API_FAILED, from),
                events.countByEventCodeAndOccurredAtGreaterThanEqual(MonitoringEventCode.BINGO_AGGREGATION_FAILED, from),
                events.countByEventCodeInAndOccurredAtGreaterThanEqual(List.of(
                        MonitoringEventCode.KILL_COMPETITION_INTERIM_FAILED,
                        MonitoringEventCode.KILL_COMPETITION_SETTLEMENT_FAILED), from),
                events.countByEventCodeInAndOccurredAtGreaterThanEqual(List.of(
                        MonitoringEventCode.DISCORD_API_FAILED, MonitoringEventCode.DISCORD_RATE_LIMIT,
                        MonitoringEventCode.JDA_CONNECTION_FAILED,
                        MonitoringEventCode.DISCORD_GUILD_CONNECTION_FAILED,
                        MonitoringEventCode.DISCORD_OAUTH_FAILED,
                        MonitoringEventCode.DISCORD_GUILD_MISMATCH,
                        MonitoringEventCode.DISCORD_MEMBER_LOOKUP_FAILED), from)
        );
        return new MonitoringResponses.Summary(server(), new MonitoringResponses.Database(databaseStatus()), last24Hours);
    }

    private MonitoringResponses.Server server() {
        long memoryUsed = metricSum("jvm.memory.used", false);
        long memoryMax = metricSum("jvm.memory.max", true);
        if (memoryUsed <= 0) memoryUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        if (memoryMax <= 0) memoryMax = Runtime.getRuntime().maxMemory();
        long diskTotal = 0;
        long diskUsed = 0;
        try {
            FileStore store = Files.getFileStore(Path.of(".").toAbsolutePath());
            diskTotal = store.getTotalSpace();
            diskUsed = Math.max(0, diskTotal - store.getUsableSpace());
        } catch (Exception failure) {
            if (shouldLog(lastDiskFailure)) log.warn("Server disk metric unavailable - jobName=monitoringStatus stage=DISK_METRIC", failure);
        }
        Double cpu = metric("process.cpu.usage");
        if (cpu == null) cpu = metric("system.cpu.usage");
        long uptime = Math.max(0, ManagementFactory.getRuntimeMXBean().getUptime() / 1_000);
        return new MonitoringResponses.Server("UP", uptime, cpu, memoryUsed, memoryMax, diskUsed, diskTotal);
    }

    private String databaseStatus() {
        try (var connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(2);
            if (!valid && shouldLog(lastDatabaseFailure)) log.error("Database health check failed - stage=CONNECTION_VALIDATION");
            return valid ? "UP" : "DOWN";
        } catch (Exception exception) {
            if (shouldLog(lastDatabaseFailure)) log.error("Database health check failed - stage=CONNECTION_VALIDATION", exception);
            return "DOWN";
        }
    }

    private boolean shouldLog(java.util.concurrent.atomic.AtomicLong last) {
        long now = System.nanoTime();
        long previous = last.get();
        return (previous == 0 || now - previous > java.util.concurrent.TimeUnit.MINUTES.toNanos(1))
                && last.compareAndSet(previous, now);
    }

    private Double metric(String name) {
        Gauge gauge = meterRegistry.find(name).gauge();
        if (gauge == null || !Double.isFinite(gauge.value()) || gauge.value() < 0) return null;
        return gauge.value();
    }

    private long metricSum(String name, boolean positiveOnly) {
        return Math.round(meterRegistry.find(name).gauges().stream().mapToDouble(Gauge::value)
                .filter(Double::isFinite).filter(value -> !positiveOnly || value > 0).sum());
    }
}
