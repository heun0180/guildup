package com.guildup.killcompetition.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KillCompetitionResultScheduler {
    private final KillCompetitionSettlementService settlements;

    public KillCompetitionResultScheduler(KillCompetitionSettlementService settlements) {
        this.settlements = settlements;
    }

    @Scheduled(fixedDelayString = "${kill-competition.result-publish-interval:60000}")
    public void publishDueResults() {
        settlements.dueCompetitionIds().forEach(settlements::publishDueResult);
    }
}
