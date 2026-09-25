package com.guildup.bingo.service;

import com.guildup.bingo.dto.BingoAggregationResponse;
import com.guildup.bingo.repository.BingoProcessedMatchRepository;
import com.guildup.pubg.model.PubgMatch;
import com.guildup.pubg.service.PubgMatchFactQueryService;
import com.guildup.pubg.service.PubgMatchSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** 외부 API 수집과 DB 계산을 transaction 밖에서 조율한다. */
@Service
public class BingoAggregationService {
    private static final Logger log = LoggerFactory.getLogger(BingoAggregationService.class);
    private final BingoAggregationPreparationService preparation;
    private final PubgMatchSyncService pubgSync;
    private final PubgMatchFactQueryService pubgFacts;
    private final BingoProcessedMatchRepository processed;
    private final BingoAggregationCalculationService calculation;
    private final BingoMatchPolicy matchPolicy;

    public BingoAggregationService(BingoAggregationPreparationService preparation, PubgMatchSyncService pubgSync,
            PubgMatchFactQueryService pubgFacts, BingoProcessedMatchRepository processed,
            BingoAggregationCalculationService calculation, BingoMatchPolicy matchPolicy) {
        this.preparation = preparation; this.pubgSync = pubgSync; this.pubgFacts = pubgFacts;
        this.processed = processed; this.calculation = calculation; this.matchPolicy = matchPolicy;
    }

    public BingoAggregationResponse aggregate(Long userId, Long communityId, Long bingoId) {
        return aggregate(userId, communityId, bingoId, PubgMatchSyncService.ProgressListener.noop());
    }

    public BingoAggregationResponse aggregate(Long userId, Long communityId, Long bingoId,
                                               PubgMatchSyncService.ProgressListener listener) {
        return aggregatePrepared(communityId, bingoId, preparation.prepareAll(userId, communityId, bingoId),
                null, listener);
    }

    public BingoAggregationResponse aggregatePersonal(Long userId, Long communityId, Long bingoId) {
        return aggregatePersonal(userId, communityId, bingoId, PubgMatchSyncService.ProgressListener.noop());
    }

    public BingoAggregationResponse aggregatePersonal(Long userId, Long communityId, Long bingoId,
                                                       PubgMatchSyncService.ProgressListener listener) {
        BingoAggregationPreparationService.PreparedAggregation prepared =
                preparation.preparePersonal(userId, communityId, bingoId);
        Long participantId = prepared.participants().getFirst().participantId();
        return aggregatePrepared(communityId, bingoId, prepared, participantId, listener);
    }

    public Long resolvePersonalParticipantId(Long userId, Long communityId, Long bingoId) {
        return preparation.resolvePersonalParticipantId(userId, communityId, bingoId);
    }

    public Long validatePersonalRequest(Long userId, Long communityId, Long bingoId) {
        return preparation.validatePersonalRequest(userId, communityId, bingoId);
    }

    private BingoAggregationResponse aggregatePrepared(Long communityId, Long bingoId,
            BingoAggregationPreparationService.PreparedAggregation prepared, Long participantId,
            PubgMatchSyncService.ProgressListener listener) {
        long totalStarted = System.nanoTime();
        Set<String> accountIds = prepared.participants().stream().map(
                BingoAggregationPreparationService.PreparedParticipant::accountId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        PubgMatchSyncService.SyncResult sync = pubgSync.sync(prepared.shard(), accountIds,
                match -> telemetryRequired(prepared, match), listener);
        listener.stage("SAVE_FACTS", sync.dbMatchesInserted(), sync.newMatchIds(), "PUBG 경기 데이터를 저장했습니다.");

        List<PubgMatchFactQueryService.StoredMatchFacts> facts = pubgFacts.findBetween(
                prepared.startsAt(), prepared.endsExclusive(), prepared.communityAccounts()).stream()
                .filter(PubgMatchFactQueryService.StoredMatchFacts::telemetryLoaded).toList();
        List<String> legacyProcessedIds = participantId == null
                ? processed.findDistinctMatchIdsByEventId(bingoId)
                : processed.findMatchIds(bingoId, participantId);
        boolean completeRecalculation = pubgFacts.existingMatchIds(legacyProcessedIds).containsAll(legacyProcessedIds);
        if (!completeRecalculation) {
            log.warn("Bingo full recalculation deferred because legacy processed matches are not in PUBG DB - eventId={}, legacyMatches={}",
                    bingoId, legacyProcessedIds.size());
        }
        listener.stage("CALCULATE_BINGO", 0, facts.size(), "저장된 경기 데이터로 빙고를 계산하고 있습니다.");
        long calculationStarted = System.nanoTime();
        BingoAggregationResponse response = participantId == null
                ? calculation.calculate(communityId, bingoId, facts, completeRecalculation)
                : calculation.calculateParticipant(communityId, bingoId, participantId, facts, completeRecalculation);
        long calculationMs = elapsedMs(calculationStarted);
        long totalMs = elapsedMs(totalStarted);
        log.info("[BINGO_AGG_SUMMARY] eventId={} participants={} accounts={} playerCalls={} discoveredMatches={} " +
                        "uniqueMatches={} existingMatches={} newMatches={} matchCalls={} matchFailures={} " +
                        "telemetryCalls={} telemetryFailures={} dbMatchesInserted={} dbPlayerFactsInserted={} " +
                        "dbKillFactsInserted={} bingoCalculationMs={} totalMs={}",
                bingoId, prepared.participantCount(), sync.linkedAccounts(), sync.playerApiCalls(),
                sync.discoveredMatchIds(), sync.discoveredMatchIds(), sync.existingDbMatches(), sync.newMatchIds(),
                sync.matchApiCalls(), sync.matchFailures(), sync.telemetryApiCalls(), sync.telemetryFailures(),
                sync.dbMatchesInserted(), sync.dbPlayerFactsInserted(), sync.dbKillFactsInserted(), calculationMs, totalMs);
        listener.stage("COMPLETED", facts.size(), facts.size(), "빙고 집계가 완료되었습니다.");
        return response.withTelemetryFailures(sync.telemetryFailures());
    }

    private boolean telemetryRequired(BingoAggregationPreparationService.PreparedAggregation prepared, PubgMatch match) {
        return match.playedAt() != null && !match.playedAt().isBefore(prepared.startsAt())
                && match.playedAt().isBefore(prepared.endsExclusive()) && matchPolicy.isEligible(match)
                && prepared.participants().stream().anyMatch(participant ->
                    !match.playedAt().isBefore(participant.eligibleFrom())
                            && match.teams().stream().flatMap(team -> team.participants().stream())
                            .anyMatch(player -> participant.accountId().equals(player.accountId())));
    }

    private long elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
