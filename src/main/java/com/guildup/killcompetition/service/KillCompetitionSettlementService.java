package com.guildup.killcompetition.service;

import com.guildup.killcompetition.dto.KillCompetitionDetailResponse;
import com.guildup.monitoring.domain.MonitoringCategory;
import com.guildup.monitoring.domain.MonitoringEventCode;
import com.guildup.monitoring.service.MonitoringEventService;
import com.guildup.pubg.exception.PubgApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class KillCompetitionSettlementService {
    private static final Logger log = LoggerFactory.getLogger(KillCompetitionSettlementService.class);
    private final KillCompetitionSettlementStore store;
    private final KillCompetitionPubgAggregator aggregator;
    private final KillCompetitionService competitions;
    private final MonitoringEventService monitoring;

    @Autowired
    public KillCompetitionSettlementService(KillCompetitionSettlementStore store,
                                            KillCompetitionPubgAggregator aggregator,
                                            KillCompetitionService competitions,
                                            MonitoringEventService monitoring) {
        this.store = store; this.aggregator = aggregator; this.competitions = competitions;
        this.monitoring = monitoring;
    }

    KillCompetitionSettlementService(KillCompetitionSettlementStore store,
                                     KillCompetitionPubgAggregator aggregator,
                                     KillCompetitionService competitions) {
        this.store = store; this.aggregator = aggregator; this.competitions = competitions;
        this.monitoring = null;
    }

    public KillCompetitionDetailResponse calculateInterim(Long userId, Long communityId, Long competitionId) {
        var work = store.claimInterim(userId, communityId, competitionId);
        try {
            var snapshot = aggregator.aggregate(work.shard(), work.startedAt(), work.rangeEnd(), work.players());
            store.finishInterim(communityId, work, snapshot);
        } catch (RuntimeException exception) {
            store.releaseInterim(communityId, work);
            recordFailure(MonitoringEventCode.KILL_COMPETITION_INTERIM_FAILED,
                    "Kill competition interim aggregation failed", userId, communityId,
                    competitionId, "INTERIM", 0, exception);
            throw exception;
        }
        return competitions.get(userId, communityId, competitionId);
    }

    public KillCompetitionDetailResponse finalizeResult(Long userId, Long communityId, Long competitionId) {
        store.requestFinal(userId, communityId, competitionId);
        if (monitoring != null) monitoring.recordInfo(MonitoringCategory.KILL_COMPETITION,
                MonitoringEventCode.KILL_COMPETITION_RESULT_PENDING,
                "Kill competition entered RESULT_PENDING", communityId, userId,
                "killCompetitionId=" + competitionId, Map.of("killCompetitionId", competitionId));
        return competitions.get(userId, communityId, competitionId);
    }

    public List<Long> dueCompetitionIds() {
        return store.findDueResultIds();
    }

    public void publishDueResult(Long competitionId) {
        var work = store.claimDueFinal(competitionId);
        if (work == null) return;
        long startedNanos = System.nanoTime();
        String stage = "CLAIM";
        log.info("Kill competition finalization competition={} claim={} stage=CLAIM_STARTED",
                competitionId, work.finalizationClaimToken());
        try {
            stage = "PUBG_FETCH";
            long pubgStartedNanos = System.nanoTime();
            log.info("Kill competition finalization competition={} claim={} stage=PUBG_FETCH_STARTED",
                    competitionId, work.finalizationClaimToken());
            var snapshot = aggregator.aggregate(work.shard(), work.startedAt(), work.rangeEnd(), work.players());
            log.info("Kill competition finalization competition={} claim={} stage=PUBG_FETCH_COMPLETED durationMs={} matches={}",
                    competitionId, work.finalizationClaimToken(), elapsedMillis(pubgStartedNanos), snapshot.matchKills().size());
            stage = "FINAL_SAVE";
            log.info("Kill competition finalization competition={} claim={} stage=FINAL_SAVE_STARTED",
                    competitionId, work.finalizationClaimToken());
            store.finishFinal(work.communityId(), work, snapshot);
            log.info("Kill competition finalization competition={} claim={} stage=FINAL_SAVE_SUCCEEDED durationMs={}",
                    competitionId, work.finalizationClaimToken(), elapsedMillis(startedNanos));
        } catch (RuntimeException exception) {
            boolean failureRecorded = false;
            try {
                failureRecorded = store.recordFinalFailure(work.communityId(), work, exception);
            } catch (RuntimeException recordException) {
                log.error("Kill competition finalization competition={} claim={} stage=FAILURE_RECORD_FAILED "
                                + "durationMs={} exceptionClass={}", competitionId, work.finalizationClaimToken(),
                        elapsedMillis(startedNanos), recordException.getClass().getSimpleName(), recordException);
            }
            log.error("Kill competition finalization competition={} claim={} stage={} durationMs={} "
                            + "failureRecorded={} exceptionClass={}; claim timeout 후 재시도합니다.",
                    competitionId, work.finalizationClaimToken(), stage, elapsedMillis(startedNanos),
                    failureRecorded, exception.getClass().getSimpleName(), exception);
            recordFailure(MonitoringEventCode.KILL_COMPETITION_SETTLEMENT_FAILED,
                    "Kill competition final settlement failed", null, work.communityId(), competitionId,
                    stage, elapsedMillis(startedNanos), exception);
        }
    }

    private void recordFailure(MonitoringEventCode code, String message, Long userId, Long communityId,
                               Long competitionId, String stage, long elapsedMs, RuntimeException exception) {
        if (monitoring == null) return;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("killCompetitionId", competitionId);
        metadata.put("stage", stage);
        metadata.put("elapsedMs", elapsedMs);
        metadata.put("exceptionClass", exception.getClass().getSimpleName());
        metadata.put("pubgApiFailure", causedByPubg(exception));
        monitoring.recordError(MonitoringCategory.KILL_COMPETITION, code, message, communityId, userId,
                "killCompetitionId=" + competitionId, metadata);
    }

    private boolean causedByPubg(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause())
            if (current instanceof PubgApiException) return true;
        return false;
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
