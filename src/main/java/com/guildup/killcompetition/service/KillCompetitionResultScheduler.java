package com.guildup.killcompetition.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.guildup.monitoring.logging.LogContext;
import com.guildup.monitoring.logging.FailureLogContext;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import java.util.List;
import java.util.Map;

@Component
public class KillCompetitionResultScheduler {
    private static final Logger log = LoggerFactory.getLogger(KillCompetitionResultScheduler.class);
    private final KillCompetitionSettlementService settlements;
    private MonitoringEventService monitoring;

    @Autowired
    void configureMonitoring(MonitoringEventService monitoring) { this.monitoring = monitoring; }

    public KillCompetitionResultScheduler(KillCompetitionSettlementService settlements) {
        this.settlements = settlements;
    }

    @Scheduled(fixedDelayString = "${kill-competition.result-publish-interval:60000}")
    public void publishDueResults() {
        long startedNanos = System.nanoTime();
        try (var ignored = LogContext.scope(Map.of("jobName", "KILL_COMPETITION_RESULT_PUBLISH"))) {
            List<Long> ids;
            try { ids = settlements.dueCompetitionIds(); }
            catch (RuntimeException failure) {
                recordFailure(null, "DUE_RESULTS_QUERY", failure);
                return;
            }
            if (ids.isEmpty()) return;
            log.info("Kill competition publication job started - jobName=KILL_COMPETITION_RESULT_PUBLISH competitions={}", ids.size());
            for (Long competitionId : ids) {
                try { settlements.publishDueResult(competitionId); }
                catch (RuntimeException failure) { recordFailure(competitionId, "PUBLISH_RESULT", failure); }
            }
            log.info("Kill competition publication job completed - jobName=KILL_COMPETITION_RESULT_PUBLISH competitions={} elapsedMs={}",
                    ids.size(), (System.nanoTime() - startedNanos) / 1_000_000);
        }
    }

    private void recordFailure(Long competitionId, String stage, RuntimeException failure) {
        if (!FailureLogContext.isLogged(failure)) {
            log.error("Kill competition publication job failed - jobName=KILL_COMPETITION_RESULT_PUBLISH killCompetitionId={} stage={}",
                    competitionId, stage, failure);
            FailureLogContext.markLogged(failure);
        }
        if (monitoring == null) return;
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("jobName", "KILL_COMPETITION_RESULT_PUBLISH");
        metadata.put("stage", stage);
        metadata.put("exceptionClass", failure.getClass().getSimpleName());
        if (competitionId != null) metadata.put("killCompetitionId", competitionId);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.DataAccessException || cause instanceof jakarta.persistence.PersistenceException) {
                monitoring.recordError(MonitoringCategory.DATABASE, MonitoringEventCode.DATABASE_ERROR,
                        "Kill competition publication database operation failed", null, null,
                        "jobName=KILL_COMPETITION_RESULT_PUBLISH", metadata);
                break;
            }
        }
        monitoring.recordError(MonitoringCategory.KILL_COMPETITION,
                MonitoringEventCode.KILL_COMPETITION_SETTLEMENT_FAILED,
                "Kill competition publication job failed", null, null,
                competitionId == null ? "jobName=KILL_COMPETITION_RESULT_PUBLISH" : "killCompetitionId=" + competitionId, metadata);
    }
}
