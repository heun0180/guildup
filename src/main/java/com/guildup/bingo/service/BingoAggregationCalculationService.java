package com.guildup.bingo.service;

import com.guildup.bingo.domain.*;
import com.guildup.bingo.dto.BingoAggregationResponse;
import com.guildup.bingo.mission.BingoMissionEngine;
import com.guildup.pubg.model.PlayerMatchFacts;
import com.guildup.bingo.repository.*;
import com.guildup.pubg.model.PubgMatch;
import com.guildup.pubg.service.PubgMatchFactQueryService.StoredMatchFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** 저장된 PUBG fact를 재생해 bingo_progress를 만드는 짧은 DB 쓰기 단계다. */
@Service
public class BingoAggregationCalculationService {
    private static final Logger log = LoggerFactory.getLogger(BingoAggregationCalculationService.class);
    private final BingoEventRepository events;
    private final BingoParticipantRepository participants;
    private final BingoProgressRepository progress;
    private final BingoProcessedMatchRepository processed;
    private final BingoMissionEngine missions;
    private final BingoMatchPolicy matchPolicy;
    private final BingoProgressCompletionService completions;
    private final Clock clock;

    public BingoAggregationCalculationService(BingoEventRepository events, BingoParticipantRepository participants,
            BingoProgressRepository progress, BingoProcessedMatchRepository processed, BingoMissionEngine missions,
            BingoMatchPolicy matchPolicy, BingoProgressCompletionService completions, Clock clock) {
        this.events = events; this.participants = participants; this.progress = progress; this.processed = processed;
        this.missions = missions; this.matchPolicy = matchPolicy; this.completions = completions; this.clock = clock;
    }

    @Transactional
    public BingoAggregationResponse calculate(Long communityId, Long bingoId, List<StoredMatchFacts> storedFacts,
                                               boolean completeRecalculation) {
        BingoEvent event = events.findForUpdate(bingoId)
                .filter(value -> value.getCommunity().getId().equals(communityId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "빙고를 찾을 수 없습니다."));
        Instant now = clock.instant();
        List<StoredMatchFacts> eligibleMatches = storedFacts.stream().filter(this::eligible)
                .sorted(Comparator.comparing(StoredMatchFacts::startedAt).thenComparing(StoredMatchFacts::matchId)).toList();
        int processedCount = 0;
        Set<Long> updated = new LinkedHashSet<>();

        for (BingoParticipant snapshot : participants.findByEventIdOrderByIdAsc(event.getId())) {
            if (snapshot.getPubgAccountId() == null) continue;
            BingoParticipant participant = participants.findForUpdate(snapshot.getId()).orElseThrow();
            ParticipantCalculation result = calculateLockedParticipant(
                    event, participant, eligibleMatches, completeRecalculation, now);
            processedCount += result.processedMatches();
            if (result.changed()) updated.add(participant.getId());
        }
        event.aggregated(now);
        if (event.getStatus() == BingoStatus.SETTLING
                && !now.isBefore(event.getMatchStartUpperBoundExclusive().plus(BingoEvent.SETTLEMENT_GRACE))) event.complete(now);
        log.info("Bingo DB calculation completed - eventId={}, mode={}, matches={}, participants={}", event.getId(),
                completeRecalculation ? "FULL" : "SAFE_INCREMENTAL", eligibleMatches.size(), updated.size());
        return new BingoAggregationResponse(event.getId(), processedCount, updated.size(), event.getStatus().name(), now);
    }

    /** 전체 집계와 같은 MissionEngine 경로를 한 참가자에만 적용한다. */
    @Transactional
    public BingoAggregationResponse calculateParticipant(Long communityId, Long bingoId, Long participantId,
            List<StoredMatchFacts> storedFacts, boolean completeRecalculation) {
        BingoEvent event = events.findWithCellsById(bingoId)
                .filter(value -> value.getCommunity().getId().equals(communityId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "빙고를 찾을 수 없습니다."));
        BingoParticipant participant = participants.findForUpdate(participantId)
                .filter(value -> value.getEvent().getId().equals(event.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "빙고 참가자를 찾을 수 없습니다."));
        Instant now = clock.instant();
        List<StoredMatchFacts> eligibleMatches = storedFacts.stream().filter(this::eligible)
                .sorted(Comparator.comparing(StoredMatchFacts::startedAt).thenComparing(StoredMatchFacts::matchId)).toList();
        ParticipantCalculation result = calculateLockedParticipant(
                event, participant, eligibleMatches, completeRecalculation, now);
        log.info("Bingo participant calculation completed - eventId={}, participantId={}, mode={}, matches={}, changed={}",
                event.getId(), participantId, completeRecalculation ? "FULL" : "SAFE_INCREMENTAL",
                eligibleMatches.size(), result.changed());
        return new BingoAggregationResponse(event.getId(), result.processedMatches(), result.changed() ? 1 : 0,
                event.getStatus().name(), now);
    }

    /** participant row lock을 획득한 뒤 progress와 processed match를 읽어 동시 계산의 stale read를 막는다. */
    private ParticipantCalculation calculateLockedParticipant(BingoEvent event, BingoParticipant participant,
            List<StoredMatchFacts> eligibleMatches, boolean completeRecalculation, Instant now) {
        List<BingoProgress> rows = progress.findByParticipantIdOrderByCellPositionAsc(participant.getId());
        Set<String> alreadyProcessed = new HashSet<>(processed.findMatchIds(event.getId(), participant.getId()));
        List<StoredMatchFacts> participantMatches = eligibleMatches.stream()
                .filter(match -> !match.startedAt().isBefore(participant.getEligibleFrom()))
                .filter(match -> match.byAccount().containsKey(participant.getPubgAccountId())).toList();
        boolean changed = false;
        boolean hasNewMatches = participantMatches.stream().anyMatch(match -> !alreadyProcessed.contains(match.matchId()));
        for (BingoProgress row : rows) {
            if (row.getCell().getMissionType().source() != BingoMissionSource.PUBG_MATCH) continue;
            if (!completeRecalculation && !hasNewMatches) continue;
            BingoMissionEngine.Outcome outcome = completeRecalculation ? BingoMissionEngine.Outcome.zero()
                    : new BingoMissionEngine.Outcome(row.getCurrentValue(), row.getOccurrenceCount(), row.isCompleted());
            String evidenceMatchId = completeRecalculation ? null : row.getEvidenceMatchId();
            Instant evidenceAt = completeRecalculation ? null : row.getEvidenceEventAt();
            for (StoredMatchFacts match : participantMatches) {
                if (!completeRecalculation && alreadyProcessed.contains(match.matchId())) continue;
                PlayerMatchFacts fact = match.byAccount().get(participant.getPubgAccountId());
                boolean wasCompleted = outcome.completed();
                outcome = missions.apply(row.getCell(), outcome, fact);
                if (!wasCompleted && outcome.completed()) {
                    evidenceMatchId = match.matchId();
                    evidenceAt = fact.latestEvidenceAt() == null ? match.startedAt() : fact.latestEvidenceAt();
                }
            }
            if (completeRecalculation) {
                boolean rowChanged = row.getCurrentValue().compareTo(outcome.value()) != 0
                        || row.getOccurrenceCount() != outcome.occurrences()
                        || row.isCompleted() != outcome.completed()
                        || !Objects.equals(row.getEvidenceMatchId(), evidenceMatchId)
                        || !Objects.equals(row.getEvidenceEventAt(), evidenceAt);
                if (rowChanged) row.replaceSnapshot(outcome.value(), outcome.occurrences(), outcome.completed(), evidenceAt,
                        evidenceMatchId, evidenceAt, now);
                changed |= rowChanged;
            } else {
                row.apply(outcome.value(), outcome.occurrences(), outcome.completed(), evidenceMatchId, evidenceAt, now);
                changed = true;
            }
        }
        int processedCount = 0;
        for (StoredMatchFacts match : participantMatches) {
            if (alreadyProcessed.add(match.matchId())) {
                processed.save(new BingoProcessedMatch(event, participant, match.matchId(), match.startedAt(), now));
                processedCount++;
                changed = true;
            }
        }
        if (changed) completions.updateLines(event, participant, rows, now);
        participant.aggregated(now);
        return new ParticipantCalculation(processedCount, changed);
    }

    private boolean eligible(StoredMatchFacts value) {
        return matchPolicy.isEligible(new PubgMatch(value.matchId(), value.startedAt(), value.gameMode(), value.mapName(),
                value.matchType(), value.customMatch(), null, List.of()));
    }

    private record ParticipantCalculation(int processedMatches, boolean changed) {}
}
