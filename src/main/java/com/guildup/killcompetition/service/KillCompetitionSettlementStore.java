package com.guildup.killcompetition.service;

import com.guildup.bingo.service.BingoGuildUpContentService;
import com.guildup.community.domain.CommunityMember;
import com.guildup.community.service.CommunityScoreService;
import com.guildup.killcompetition.domain.*;
import com.guildup.killcompetition.repository.*;
import com.guildup.pubg.support.PubgGameSupport;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import org.springframework.data.domain.PageRequest;

@Service
public class KillCompetitionSettlementStore {
    static final Duration INTERIM_COOLDOWN = Duration.ofMinutes(5);
    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(10);

    public record SettlementWork(Long competitionId, Long communityId, String shard, Instant startedAt, Instant rangeEnd,
                                 Instant claimAt, List<KillCompetitionPubgAggregator.PlayerInput> players) {}

    private final KillCompetitionRepository competitions;
    private final KillCompetitionMatchResultRepository matchResults;
    private final KillCompetitionManagementAccess managementAccess;
    private final CommunityScoreService scores;
    private final BingoGuildUpContentService bingoContent;
    private final KillCompetitionWinnerResolver winnerResolver;
    private final Clock clock;

    public KillCompetitionSettlementStore(KillCompetitionRepository competitions,
                                          KillCompetitionMatchResultRepository matchResults,
                                          KillCompetitionManagementAccess managementAccess,
                                          CommunityScoreService scores,
                                          BingoGuildUpContentService bingoContent,
                                          KillCompetitionWinnerResolver winnerResolver, Clock clock) {
        this.competitions = competitions; this.matchResults = matchResults;
        this.managementAccess = managementAccess; this.scores = scores;
        this.bingoContent = bingoContent; this.winnerResolver = winnerResolver; this.clock = clock;
    }

    @Transactional
    public SettlementWork claimInterim(Long userId, Long communityId, Long competitionId) {
        KillCompetition competition = requireForUpdate(communityId, competitionId);
        managementAccess.requireCanManage(userId, communityId, competition);
        Instant now = clock.instant();
        if (competition.getStatus() != KillCompetitionStatus.IN_PROGRESS || !now.isBefore(competition.getEndsAt())) {
            conflict("진행 중인 킬내기만 중간 정산할 수 있습니다.");
        }
        if (competition.getLastInterimCalculatedAt() != null
                && now.isBefore(competition.getLastInterimCalculatedAt().plus(INTERIM_COOLDOWN))) {
            conflict("마지막 정산 후 5분이 지나야 다시 정산할 수 있습니다.");
        }
        if (activeClaim(competition.getInterimCalculationStartedAt(), now)) conflict("다른 참가자가 정산 중입니다.");
        competition.beginInterim(now);
        return work(competition, now, now);
    }

    @Transactional
    public void finishInterim(Long communityId, SettlementWork work, KillCompetitionKillSnapshot snapshot) {
        KillCompetition competition = requireForUpdate(communityId, work.competitionId());
        if (!Objects.equals(competition.getInterimCalculationStartedAt(), work.claimAt())) {
            conflict("중간 정산 요청이 만료되었습니다. 다시 시도해 주세요.");
        }
        List<KillCompetitionMatchResult> accumulated = mergeMatchResults(competition, snapshot, work.rangeEnd());
        applyTotals(competition, accumulated, false);
        Instant latest = accumulated.stream().map(KillCompetitionMatchResult::getMatchStartedAt)
                .max(Comparator.naturalOrder()).orElse(null);
        competition.finishInterim(clock.instant(), latest);
    }

    @Transactional
    public void requestFinal(Long userId, Long communityId, Long competitionId) {
        KillCompetition competition = requireForUpdate(communityId, competitionId);
        managementAccess.requireCanManage(userId, communityId, competition);
        Instant now = clock.instant();
        if (competition.getStatus() == KillCompetitionStatus.RESULT_PENDING) conflict("이미 결과 발표를 요청했습니다.");
        if (competition.getStatus() == KillCompetitionStatus.COMPLETED) conflict("이미 결과가 확정된 킬내기입니다.");
        if (competition.getStatus() != KillCompetitionStatus.IN_PROGRESS || now.isBefore(competition.getEndsAt())) {
            conflict("종료된 킬내기만 결과를 발표할 수 있습니다.");
        }
        competition.requestResult(now, now.plus(Duration.ofMinutes(30)));
    }

    @Transactional(readOnly = true)
    public List<Long> findDueResultIds() {
        return competitions.findResultPublishCandidateIds(clock.instant(), PageRequest.of(0, 20));
    }

    @Transactional
    public SettlementWork claimDueFinal(Long competitionId) {
        KillCompetition competition = competitions.findByIdForUpdate(competitionId).orElse(null);
        if (competition == null) return null;
        Instant now = clock.instant();
        if (competition.getStatus() != KillCompetitionStatus.RESULT_PENDING
                || competition.getResultPublishAt() == null || competition.getResultPublishAt().isAfter(now)
                || activeClaim(competition.getFinalizationStartedAt(), now)) return null;
        competition.beginFinalization(now);
        return work(competition, competition.getEndsAt(), now);
    }

    /** 외부 API 계산이 끝난 뒤 결과, 점수 원장, COMPLETED 상태를 짧은 한 트랜잭션으로 확정한다. */
    @Transactional
    public void finishFinal(Long communityId, SettlementWork work, KillCompetitionKillSnapshot snapshot) {
        KillCompetition competition = requireForUpdate(communityId, work.competitionId());
        if (competition.getStatus() == KillCompetitionStatus.COMPLETED) return;
        if (competition.getStatus() != KillCompetitionStatus.RESULT_PENDING) conflict("결과 발표 대기 상태가 아닙니다.");
        if (!Objects.equals(competition.getFinalizationStartedAt(), work.claimAt())) {
            conflict("결과 발표 요청이 만료되었습니다. 다시 시도해 주세요.");
        }
        List<KillCompetitionMatchResult> accumulated = mergeMatchResults(competition, snapshot, work.rangeEnd());
        applyTotals(competition, accumulated, true);

        Instant completedAt = clock.instant();
        List<CommunityMember> winners = winnerResolver.winnerMembers(competition);
        if (competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved).count() >= 4) {
            winners.stream().sorted(Comparator.comparing(CommunityMember::getId))
                    .forEach(member -> scores.addKillCompetitionWinIfEligible(member, competition.getId(), completedAt));
        }
        competition.complete(completedAt);
        bingoContent.applyCompletedKillCompetition(competition, winners, completedAt);
    }

    @Transactional
    public void releaseInterim(Long communityId, SettlementWork work) {
        competitions.findForUpdate(communityId, work.competitionId()).ifPresent(competition -> {
            if (Objects.equals(competition.getInterimCalculationStartedAt(), work.claimAt())) competition.clearInterimClaim();
        });
    }

    @Transactional
    public void recordFinalFailure(Long communityId, SettlementWork work, RuntimeException failure) {
        competitions.findForUpdate(communityId, work.competitionId()).ifPresent(competition -> {
            if (Objects.equals(competition.getFinalizationStartedAt(), work.claimAt())) {
                competition.recordResultFailure(failure.getMessage(), clock.instant());
            }
        });
    }

    private SettlementWork work(KillCompetition competition, Instant end, Instant claimAt) {
        String shard = PubgGameSupport.requireShard(competition.getCommunityGame().getGameType());
        return new SettlementWork(competition.getId(), competition.getCommunity().getId(), shard,
                competition.getStartedAt(), end, claimAt,
                competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved)
                        .map(p -> new KillCompetitionPubgAggregator.PlayerInput(
                                p.getId(), p.getPubgAccountId(), p.getEligibleFrom() == null
                                        ? competition.getStartedAt() : p.getEligibleFrom())).toList());
    }

    private List<KillCompetitionMatchResult> mergeMatchResults(KillCompetition competition,
                                                               KillCompetitionKillSnapshot snapshot,
                                                               Instant rangeEnd) {
        List<KillCompetitionMatchResult> accumulated = new ArrayList<>(
                matchResults.findByCompetitionIdOrderByMatchStartedAtAscMatchIdAscParticipantIdAsc(competition.getId()));
        record MatchKey(Long participantId, String matchId) {}
        Map<MatchKey, KillCompetitionMatchResult> byKey = accumulated.stream().collect(java.util.stream.Collectors.toMap(
                row -> new MatchKey(row.getParticipant().getId(), row.getMatchId()), row -> row));
        Map<Long, KillCompetitionParticipant> participantsById = competition.getParticipants().stream()
                .filter(KillCompetitionParticipant::isApproved)
                .collect(java.util.stream.Collectors.toMap(KillCompetitionParticipant::getId, participant -> participant));
        List<KillCompetitionMatchResult> discovered = new ArrayList<>();
        for (KillCompetitionKillSnapshot.MatchKill row : snapshot.matchKills()) {
            KillCompetitionParticipant participant = participantsById.get(row.participantId());
            if (participant == null) throw new IllegalStateException("참가자 정산 결과가 올바르지 않습니다.");
            Instant eligibleFrom = participant.getEligibleFrom() == null
                    ? competition.getStartedAt() : participant.getEligibleFrom();
            Instant lowerBound = eligibleFrom.isAfter(competition.getStartedAt())
                    ? eligibleFrom : competition.getStartedAt();
            if (row.startedAt() == null || row.startedAt().isBefore(lowerBound)
                    || !row.startedAt().isBefore(rangeEnd)) continue;
            MatchKey key = new MatchKey(row.participantId(), row.matchId());
            KillCompetitionMatchResult existing = byKey.get(key);
            if (existing != null) {
                existing.refresh(row.startedAt(), row.kills());
                continue;
            }
            KillCompetitionMatchResult result = new KillCompetitionMatchResult(
                    competition, participant, row.matchId(), row.startedAt(), row.kills());
            byKey.put(key, result);
            discovered.add(result);
            accumulated.add(result);
        }
        if (!discovered.isEmpty()) matchResults.saveAll(discovered);
        return accumulated;
    }

    private void applyTotals(KillCompetition competition, List<KillCompetitionMatchResult> accumulated,
                             boolean finalResult) {
        Map<Long, KillCompetitionKillSnapshot.PlayerTotal> totals = new HashMap<>();
        for (KillCompetitionMatchResult row : accumulated) {
            Long participantId = row.getParticipant().getId();
            KillCompetitionKillSnapshot.PlayerTotal old = totals.getOrDefault(
                    participantId, new KillCompetitionKillSnapshot.PlayerTotal(0, 0));
            totals.put(participantId, new KillCompetitionKillSnapshot.PlayerTotal(
                    Math.addExact(old.kills(), row.getKills()), Math.addExact(old.matchCount(), 1)));
        }
        for (KillCompetitionParticipant participant : competition.getParticipants().stream()
                .filter(KillCompetitionParticipant::isApproved).toList()) {
            var total = totals.getOrDefault(participant.getId(),
                    new KillCompetitionKillSnapshot.PlayerTotal(0, 0));
            if (finalResult) participant.recordFinal(total.kills(), total.matchCount());
            else participant.recordInterim(total.kills(), total.matchCount());
        }
    }

    private boolean activeClaim(Instant claim, Instant now) {
        return claim != null && now.isBefore(claim.plus(CLAIM_TIMEOUT));
    }
    private KillCompetition requireForUpdate(Long communityId, Long id) {
        return competitions.findForUpdate(communityId, id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "킬내기를 찾을 수 없습니다."));
    }
    private void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
