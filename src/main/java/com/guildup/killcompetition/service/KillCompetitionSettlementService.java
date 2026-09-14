package com.guildup.killcompetition.service;

import com.guildup.killcompetition.dto.KillCompetitionDetailResponse;
import org.springframework.stereotype.Service;

@Service
public class KillCompetitionSettlementService {
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
        var work = store.claimFinal(userId, communityId, competitionId);
        try {
            var snapshot = aggregator.aggregate(work.shard(), work.startedAt(), work.rangeEnd(), work.players());
            store.finishFinal(communityId, work, snapshot);
        } catch (RuntimeException exception) {
            store.releaseFinal(communityId, work);
            throw exception;
        }
        return competitions.get(userId, communityId, competitionId);
    }
}
