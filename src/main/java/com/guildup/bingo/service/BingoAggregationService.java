package com.guildup.bingo.service;

import com.guildup.bingo.domain.*;
import com.guildup.bingo.dto.BingoAggregationResponse;
import com.guildup.bingo.mission.*;
import com.guildup.bingo.repository.*;
import com.guildup.community.domain.CommunityGame;
import com.guildup.community.repository.CommunityGameRepository;
import com.guildup.community.repository.CommunityRepository;
import com.guildup.community.service.CommunityAccessService;
import com.guildup.pubg.model.*;
import com.guildup.pubg.service.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BingoAggregationService {
    private final BingoEventRepository events; private final BingoParticipantRepository participants;
    private final BingoProgressRepository progress; private final BingoProcessedMatchRepository processed;
    private final BingoLineCompletionRepository lineCompletions; private final CommunityGameRepository games;
    private final CommunityRepository communities;
    private final CommunityAccessService access; private final BingoParticipantEnrollmentService enrollment;
    private final PubgPlayerService players; private final PubgMatchService matches; private final PubgBingoFactService facts;
    private final BingoMissionEngine missions; private final BingoLineCalculator lineCalculator; private final Clock clock;

    public BingoAggregationService(BingoEventRepository events, BingoParticipantRepository participants,
            BingoProgressRepository progress, BingoProcessedMatchRepository processed,
            BingoLineCompletionRepository lineCompletions, CommunityGameRepository games, CommunityRepository communities,
            CommunityAccessService access, BingoParticipantEnrollmentService enrollment,
            PubgPlayerService players, PubgMatchService matches, PubgBingoFactService facts,
            BingoMissionEngine missions, BingoLineCalculator lineCalculator, Clock clock) {
        this.events=events; this.participants=participants; this.progress=progress; this.processed=processed;
        this.lineCompletions=lineCompletions; this.games=games; this.communities=communities;
        this.access=access; this.enrollment=enrollment;
        this.players=players; this.matches=matches; this.facts=facts; this.missions=missions;
        this.lineCalculator=lineCalculator; this.clock=clock;
    }

    @Transactional
    public BingoAggregationResponse aggregate(Long userId, Long communityId, Long bingoId) {
        access.requireCommunityAdmin(userId, communityId); Instant now = clock.instant();
        communities.findForUpdate(communityId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "빙고를 찾을 수 없습니다."));
        List<BingoEvent> communityEvents = events.findByCommunityIdForUpdate(communityId);
        communityEvents.stream().filter(value -> value.getStatus() == BingoStatus.ACTIVE)
                .forEach(value -> value.refreshStatus(now));
        BingoEvent event = events.findForUpdate(bingoId)
                .filter(value -> value.getCommunity().getId().equals(communityId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "빙고를 찾을 수 없습니다."));
        boolean anotherActive = communityEvents.stream().anyMatch(value -> !value.getId().equals(event.getId())
                && value.getStatus() == BingoStatus.ACTIVE);
        if (!anotherActive) event.refreshStatus(now);
        if (event.getStatus() != BingoStatus.ACTIVE && event.getStatus() != BingoStatus.SETTLING)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "진행 중이거나 정산 중인 빙고만 집계할 수 있습니다.");
        if (event.getLastAggregatedAt() != null
                && now.isBefore(event.getLastAggregatedAt().plus(BingoEvent.AGGREGATION_COOLDOWN)))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "빙고 집계는 30분에 한 번만 실행할 수 있습니다.");
        enrollment.enrollEligible(event, now);
        List<BingoParticipant> participantRows = participants.findByEventIdOrderByIdAsc(event.getId());
        List<BingoParticipant> connected = participantRows.stream().filter(p -> p.getPubgAccountId() != null).toList();
        if (connected.isEmpty()) {
            event.aggregated(now);
            if (event.getStatus() == BingoStatus.SETTLING
                    && !now.isBefore(event.getEndsAt().plus(BingoEvent.SETTLEMENT_GRACE))) event.complete(now);
            return new BingoAggregationResponse(event.getId(), 0, 0, event.getStatus().name(), now);
        }
        String shard = games.findByCommunityIdOrderByIdAsc(communityId).stream()
                .map(CommunityGame::getGameType).filter(type -> type.supportsRosterActivityRule())
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "배틀그라운드 게임 설정을 찾을 수 없습니다.")).getPubgShard();
        Map<String, BingoParticipant> byAccount = connected.stream().collect(Collectors.toMap(
                BingoParticipant::getPubgAccountId, Function.identity(), (a,b)->a, LinkedHashMap::new));
        List<PubgPlayer> loadedPlayers = players.findByAccountIdsFresh(shard, new ArrayList<>(byAccount.keySet()));
        Map<String, Set<String>> accountsByMatch = new LinkedHashMap<>();
        loadedPlayers.forEach(player -> player.matchIds().forEach(matchId ->
                accountsByMatch.computeIfAbsent(matchId, ignored -> new LinkedHashSet<>()).add(player.accountId())));
        Set<String> matchIds = accountsByMatch.entrySet().stream().filter(entry -> entry.getValue().stream().anyMatch(account -> {
                    BingoParticipant participant = byAccount.get(account);
                    return participant != null && !processed.existsByEventIdAndParticipantIdAndMatchId(
                            event.getId(), participant.getId(), entry.getKey());
                })).map(Map.Entry::getKey).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, PubgMatch> loadedMatches = event.getStatus() == BingoStatus.SETTLING
                ? matches.findUniqueMatchesFresh(shard, matchIds)
                : matches.findUniqueMatches(shard, matchIds);
        List<PubgMatch> ordered = loadedMatches.values().stream().filter(match -> match.playedAt() != null)
                .sorted(Comparator.comparing(PubgMatch::playedAt).thenComparing(PubgMatch::matchId)).toList();
        int processedCount = 0; Set<Long> updated = new LinkedHashSet<>();
        Set<String> communityAccounts = byAccount.keySet();
        for (PubgMatch match : ordered) {
            if (match.playedAt().isBefore(event.getStartsAt()) || match.playedAt().isAfter(event.getEndsAt())) continue;
            boolean needed = byAccount.values().stream().anyMatch(participant ->
                    !match.playedAt().isBefore(participant.getEligibleFrom())
                    && !processed.existsByEventIdAndParticipantIdAndMatchId(event.getId(), participant.getId(), match.matchId()));
            if (!needed) continue;
            Map<String, PlayerMatchFacts> matchFacts = facts.facts(match, communityAccounts);
            for (Map.Entry<String, BingoParticipant> entry : byAccount.entrySet()) {
                BingoParticipant participant = entry.getValue(); PlayerMatchFacts playerFacts = matchFacts.get(entry.getKey());
                if (playerFacts == null || match.playedAt().isBefore(participant.getEligibleFrom())) continue;
                if (processed.existsByEventIdAndParticipantIdAndMatchId(event.getId(), participant.getId(), match.matchId())) continue;
                BingoParticipant locked = participants.findForUpdate(participant.getId()).orElseThrow();
                List<BingoProgress> rows = progress.findByParticipantIdOrderByCellPositionAsc(locked.getId());
                for (BingoProgress row : rows) {
                    BingoMissionEngine.Outcome outcome = missions.apply(row.getCell(), row, playerFacts);
                    row.apply(outcome.value(), outcome.occurrences(), outcome.completed(), match.matchId(),
                            playerFacts.latestEvidenceAt(), now);
                }
                processed.save(new BingoProcessedMatch(event, locked, match.matchId(), match.playedAt(), now));
                updateLines(event, locked, rows, playerFacts.latestEvidenceAt() == null ? match.playedAt() : playerFacts.latestEvidenceAt());
                processedCount++; updated.add(locked.getId());
            }
        }
        event.aggregated(now);
        if (event.getStatus() == BingoStatus.SETTLING && !now.isBefore(event.getEndsAt().plus(BingoEvent.SETTLEMENT_GRACE))) event.complete(now);
        return new BingoAggregationResponse(event.getId(), processedCount, updated.size(), event.getStatus().name(), now);
    }

    private void updateLines(BingoEvent event, BingoParticipant participant, List<BingoProgress> rows, Instant completedAt) {
        Set<Integer> positions = rows.stream().filter(BingoProgress::isCompleted)
                .map(row -> row.getCell().getPosition()).collect(Collectors.toSet());
        Set<String> completed = lineCalculator.completedLines(event.getBoardSize(), positions);
        Set<String> existing = lineCompletions.findByParticipantId(participant.getId()).stream()
                .map(BingoLineCompletion::getLineKey).collect(Collectors.toSet());
        completed.stream().filter(key -> !existing.contains(key))
                .forEach(key -> lineCompletions.save(new BingoLineCompletion(participant, key, completedAt)));
        boolean blackout = rows.size() == event.getBoardSize() * event.getBoardSize()
                && rows.stream().allMatch(BingoProgress::isCompleted);
        participant.updateLines(completed.size(), completed.size() >= event.getTargetLines(),
                event.isBlackoutEnabled() && blackout, completedAt);
    }
}
