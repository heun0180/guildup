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
        listener.stage("PROCESSED_MATCH_QUERY", 0, 0, "기존 집계 이력을 확인하고 있습니다.");
        List<String> legacyProcessedIds = participantId == null
                ? processed.findDistinctMatchIdsByEventId(bingoId)
                : processed.findMatchIds(bingoId, participantId);
        Set<String> retryIds = new java.util.LinkedHashSet<>(prepared.pendingMatchIds());
        retryIds.addAll(legacyProcessedIds);
        PubgMatchSyncService.SyncResult sync = pubgSync.sync(prepared.platform(), accountIds,
                match -> telemetryRequired(prepared, match), prepared.excludeBotCombatStats(), listener, retryIds);
        listener.stage("SAVE_FACTS", sync.dbMatchesInserted(), sync.newMatchIds(), "PUBG 경기 데이터를 저장했습니다.");

        listener.stage("FACT_QUERY", 0, 0, "저장된 경기 Fact를 조회하고 있습니다.");
        List<PubgMatchFactQueryService.StoredMatchFacts> stored = pubgFacts.findBetween(
                prepared.platform(), prepared.startsAt(), prepared.endsExclusive(), accountIds, prepared.communityAccounts()).stream()
                .filter(match -> relevant(prepared, match)).toList();
        if (prepared.excludeBotCombatStats() && stored.stream().anyMatch(match -> !match.telemetryLoaded()
                || match.telemetryFactVersion() < com.guildup.pubg.domain.PubgStoredMatch.CURRENT_TELEMETRY_FACT_VERSION)) {
            throw new IllegalStateException("AI 봇 제외용 Telemetry Fact를 모두 준비하지 못했습니다. 잠시 후 다시 집계해 주세요.");
        }
        List<PubgMatchFactQueryService.StoredMatchFacts> facts = stored.stream()
                .filter(PubgMatchFactQueryService.StoredMatchFacts::telemetryLoaded).toList();
        Set<String> legacyIds = Set.copyOf(legacyProcessedIds);
        // An already-counted match awaiting Telemetry retry must not erase existing progress.
        boolean legacyComplete = pubgFacts.existingMatchIds(prepared.platform(), legacyProcessedIds).containsAll(legacyProcessedIds)
                && stored.stream().noneMatch(match -> legacyIds.contains(match.matchId()) && !match.telemetryLoaded());
        List<PubgMatchSyncService.CollectionFailure> failures = new java.util.ArrayList<>(sync.failures());
        stored.stream().filter(match -> !match.telemetryLoaded())
                .filter(match -> failures.stream().noneMatch(failure -> failure.dataId().equals(match.matchId())))
                .forEach(match -> failures.add(new PubgMatchSyncService.CollectionFailure(
                        PubgMatchSyncService.FailureStage.FACT_GENERATION, match.matchId())));
        boolean collectionComplete = failures.isEmpty();
        boolean completeRecalculation = legacyComplete && collectionComplete;
        if (!completeRecalculation) {
            log.warn("Bingo full recalculation deferred because required facts are incomplete - eventId={} legacyComplete={} failures={}",
                    bingoId, legacyComplete, failures);
        }
        if (!collectionComplete) {
            log.warn("Bingo final settlement deferred - eventId={} failures={} incompleteTelemetry={}", bingoId,
                    failures, stored.stream().filter(match -> !match.telemetryLoaded())
                            .map(PubgMatchFactQueryService.StoredMatchFacts::matchId).toList());
            listener.stage("COLLECTION_INCOMPLETE", failures.size(), failures.size(),
                    "필요한 경기 데이터 수집이 끝나지 않아 최종 확정을 보류합니다: " + failures.stream()
                            .map(PubgMatchSyncService.CollectionFailure::description)
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
        listener.stage("CALCULATE_BINGO", 0, facts.size(), "저장된 경기 데이터로 빙고를 계산하고 있습니다.");
        long calculationStarted = System.nanoTime();
        BingoAggregationResponse response = participantId == null
                ? calculation.calculate(communityId, bingoId, facts, completeRecalculation, prepared,
                        collectionComplete, sync.pendingMatchIds())
                : calculation.calculateParticipant(communityId, bingoId, participantId, facts, completeRecalculation,
                        prepared, sync.pendingMatchIds());
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
        return response.withCollectionFailures(sync.telemetryFailures(), failures);
    }

    private boolean relevant(BingoAggregationPreparationService.PreparedAggregation prepared,
            PubgMatchFactQueryService.StoredMatchFacts stored) {
        if (stored.platform() != prepared.platform()) return false;
        PubgMatch match = new PubgMatch(stored.matchId(), stored.startedAt(), stored.gameMode(), stored.mapName(),
                stored.matchType(), stored.customMatch(), null, List.of());
        return matchPolicy.isEligible(match) && prepared.participants().stream().anyMatch(participant ->
                !stored.startedAt().isBefore(participant.eligibleFrom()) && stored.byAccount().containsKey(participant.accountId()));
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
