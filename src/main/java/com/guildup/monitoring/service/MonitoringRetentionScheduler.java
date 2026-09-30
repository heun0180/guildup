package com.guildup.monitoring.service;

import com.guildup.monitoring.repository.MonitoringEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

@Component
public class MonitoringRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(MonitoringRetentionScheduler.class);
    private static final int DELETE_BATCH_SIZE = 500;
    private final MonitoringEventRepository events;
    private final Clock clock;
    private final int retentionDays;

    public MonitoringRetentionScheduler(MonitoringEventRepository events, Clock clock,
                                        @Value("${monitoring.retention-days:30}") int retentionDays) {
        this.events = events;
        this.clock = clock;
        this.retentionDays = Math.max(1, retentionDays);
    }

    @Scheduled(cron = "${monitoring.cleanup-cron:0 20 4 * * *}")
    public void deleteExpiredEvents() {
        var cutoff = clock.instant().minus(retentionDays, ChronoUnit.DAYS);
        int total = 0;
        while (true) {
            var batch = events.findByOccurredAtBeforeOrderByOccurredAtAsc(
                    cutoff, PageRequest.of(0, DELETE_BATCH_SIZE));
            if (batch.isEmpty()) break;
            events.deleteAllInBatch(batch);
            total += batch.size();
            if (batch.size() < DELETE_BATCH_SIZE) break;
        }
        if (total > 0) log.info("Expired monitoring events deleted - count={} retentionDays={}", total, retentionDays);
    }
}
