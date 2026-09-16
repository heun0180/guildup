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
        try {
            var snapshot = aggregator.aggregate(work.shard(), work.startedAt(), work.rangeEnd(), work.players());
            store.finishFinal(work.communityId(), work, snapshot);
        } catch (RuntimeException exception) {
            store.recordFinalFailure(work.communityId(), work, exception);
            log.warn("킬내기 {} 자동 결과 발표 실패; claim 만료 후 재시도합니다.", competitionId, exception);
        }
    }
}
