package com.guildup.monitoring.service;

import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Map;

@Component
public class DatabasePoolMonitor {
    private static final String REFERENCE = "hikariPool";
    private final DataSource dataSource;
    private final MonitoringEventService monitoring;

    public DatabasePoolMonitor(DataSource dataSource, MonitoringEventService monitoring) {
        this.dataSource = dataSource;
        this.monitoring = monitoring;
    }

    @Scheduled(fixedDelayString = "${monitoring.database.pool-check-interval:1m}")
    public void checkPoolPressure() {
        if (!(dataSource instanceof HikariDataSource hikari) || hikari.getHikariPoolMXBean() == null) return;
        var pool = hikari.getHikariPoolMXBean();
        int pending = pool.getThreadsAwaitingConnection();
        if (pending <= 0 || monitoring.wasRecordedRecently(MonitoringEventCode.DATABASE_POOL_EXHAUSTED,
                REFERENCE, Duration.ofMinutes(10))) return;
        monitoring.recordWarn(MonitoringCategory.DATABASE, MonitoringEventCode.DATABASE_POOL_EXHAUSTED,
                "Database connection pool has waiting requests", null, null, REFERENCE,
                Map.of("activeConnections", pool.getActiveConnections(),
                        "idleConnections", pool.getIdleConnections(),
                        "totalConnections", pool.getTotalConnections(),
                        "threadsAwaitingConnection", pending));
    }
}
