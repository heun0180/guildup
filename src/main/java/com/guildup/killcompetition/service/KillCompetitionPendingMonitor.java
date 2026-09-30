package com.guildup.killcompetition.service;

import com.guildup.killcompetition.repository.KillCompetitionRepository;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

@Component
public class KillCompetitionPendingMonitor {
    private static final Duration STALE_AFTER = Duration.ofHours(1);
    private static final Duration DEDUPLICATION_WINDOW = Duration.ofHours(12);
    private final KillCompetitionRepository competitions;
    private final MonitoringEventService monitoring;
    private final Clock clock;

    public KillCompetitionPendingMonitor(KillCompetitionRepository competitions,
                                         MonitoringEventService monitoring, Clock clock) {
        this.competitions = competitions;
        this.monitoring = monitoring;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${monitoring.kill-competition.pending-check-interval:15m}")
    @Transactional(readOnly = true)
    public void recordStalePendingResults() {
        var now = clock.instant();
        competitions.findStaleResultPending(now.minus(STALE_AFTER), PageRequest.of(0, 100))
                .forEach(competition -> {
                    String reference = "killCompetitionId=" + competition.getId();
                    if (monitoring.wasRecordedRecently(MonitoringEventCode.KILL_COMPETITION_RESULT_PENDING_STALE,
                            reference, DEDUPLICATION_WINDOW)) return;
                    monitoring.recordWarn(MonitoringCategory.KILL_COMPETITION,
                            MonitoringEventCode.KILL_COMPETITION_RESULT_PENDING_STALE,
                            "Kill competition has remained RESULT_PENDING for over one hour",
                            competition.getCommunity().getId(), null, reference,
                            Map.of("killCompetitionId", competition.getId(),
                                    "pendingSeconds", Duration.between(competition.getResultRequestedAt(), now).toSeconds()));
                });
    }
}
