package com.guildup.killcompetition.service;

import com.guildup.killcompetition.dto.KillCompetitionDetailResponse;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Service
public class KillCompetitionSettlementService {
    private static final Logger log = LoggerFactory.getLogger(KillCompetitionSettlementService.class);
    private final KillCompetitionSettlementStore store;
    private final KillCompetitionPubgAggregator aggregator;
    private final KillCompetitionService competitions;

    public KillCompetitionSettlementService(KillCompetitionSettlementStore store,
                                            KillCompetitionPubgAggregator aggregator,
                                            KillCompetitionService competitions) {
        this.store = store; this.aggregator = aggregator; this.competitions = competitions;
    }

    public KillCompetitionDetailResponse calculateInterim(Long userId, Long communityId, Long competitionId) {
        var work = store.claimInterim(userId, communityId, competitionId);
        try {
            var snapshot = aggregator.aggregate(work.shard(), work.startedAt(), work.rangeEnd(), work.players());
            store.finishInterim(communityId, work, snapshot);
        } catch (RuntimeException exception) {
            store.releaseInterim(communityId, work);
            throw exception;
        }
        return competitions.get(userId, communityId, competitionId);
    }

    public KillCompetitionDetailResponse finalizeResult(Long userId, Long communityId, Long competitionId) {
        store.requestFinal(userId, communityId, competitionId);
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
                                + "durationMs={} error={}", competitionId, work.finalizationClaimToken(),
                        elapsedMillis(startedNanos), recordException.getMessage(), recordException);
            }
            log.warn("Kill competition finalization competition={} claim={} stage={} durationMs={} "
                            + "failureRecorded={} error={}; claim timeout 후 재시도합니다.",
                    competitionId, work.finalizationClaimToken(), stage, elapsedMillis(startedNanos),
                    failureRecorded, exception.getMessage(), exception);
        }
    }

    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
