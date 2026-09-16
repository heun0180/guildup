package com.guildup.killcompetition.service;

import com.guildup.community.domain.CommunityMember;
import com.guildup.community.repository.CommunityGameRepository;
import com.guildup.community.service.CommunityAccessService;
import com.guildup.community.service.CommunityScoreService;
import com.guildup.community.service.CurrentCommunityMemberService;
import com.guildup.killcompetition.domain.*;
import com.guildup.killcompetition.repository.*;
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
    private final CommunityGameRepository communityGames;
    private final CurrentCommunityMemberService currentMembers;
    private final CommunityAccessService access;
    private final CommunityScoreService scores;
    private final Clock clock;

    public KillCompetitionSettlementStore(KillCompetitionRepository competitions,
                                          KillCompetitionMatchResultRepository matchResults,
                                          CommunityGameRepository communityGames,
                                          CurrentCommunityMemberService currentMembers,
                                          CommunityAccessService access,
                                          CommunityScoreService scores, Clock clock) {
        this.competitions = competitions; this.matchResults = matchResults; this.communityGames = communityGames;
        this.currentMembers = currentMembers; this.access = access; this.scores = scores; this.clock = clock;
    }

    @Transactional
    public SettlementWork claimInterim(Long userId, Long communityId, Long competitionId) {
        access.requireCommunityMember(userId, communityId);
        KillCompetition competition = requireForUpdate(communityId, competitionId);
        CommunityMember member = currentMembers.require(userId, communityId);
        if (competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved).noneMatch(p -> Objects.equals(
                p.getCommunityMember().getId(), member.getId()))) forbidden("승인된 참가자만 중간 정산을 할 수 있습니다.");
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
        applyTotals(competition, snapshot, false);
        Instant latest = snapshot.matchKills().stream().map(KillCompetitionKillSnapshot.MatchKill::startedAt)
                .max(Comparator.naturalOrder()).orElse(null);
        competition.finishInterim(clock.instant(), latest);
    }

    @Transactional
    public void requestFinal(Long userId, Long communityId, Long competitionId) {
        access.requireCommunityMember(userId, communityId);
        KillCompetition competition = requireForUpdate(communityId, competitionId);
        CommunityMember member = currentMembers.require(userId, communityId);
        if (!Objects.equals(competition.getCreatedBy().getId(), member.getId())) forbidden("킬내기 생성자만 결과를 발표할 수 있습니다.");
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
        applyTotals(competition, snapshot, true);
        matchResults.deleteByCompetitionId(competition.getId());
        Map<Long, KillCompetitionParticipant> byId = new HashMap<>();
        competition.getParticipants().forEach(p -> byId.put(p.getId(), p));
        matchResults.saveAll(snapshot.matchKills().stream().map(row -> new KillCompetitionMatchResult(
                competition, byId.get(row.participantId()), row.matchId(), row.startedAt(), row.kills())).toList());

        Instant completedAt = clock.instant();
        if (competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved).count() >= 4) {
            winnerMembers(competition).stream().sorted(Comparator.comparing(CommunityMember::getId))
                    .forEach(member -> scores.addKillCompetitionWinIfEligible(member, competition.getId(), completedAt));
        }
        competition.complete(completedAt);
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
        String shard = communityGames.findFirstByCommunityIdOrderByIdAsc(competition.getCommunity().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "커뮤니티 PUBG 게임 설정이 없습니다."))
                .getGameType().getPubgShard();
        return new SettlementWork(competition.getId(), competition.getCommunity().getId(), shard,
                competition.getStartedAt(), end, claimAt,
                competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved)
                        .map(p -> new KillCompetitionPubgAggregator.PlayerInput(
                                p.getId(), p.getPubgAccountId(), p.getEligibleFrom() == null
                                        ? competition.getStartedAt() : p.getEligibleFrom())).toList());
    }

    private void applyTotals(KillCompetition competition, KillCompetitionKillSnapshot snapshot, boolean finalResult) {
        for (KillCompetitionParticipant participant : competition.getParticipants().stream()
                .filter(KillCompetitionParticipant::isApproved).toList()) {
            var total = snapshot.totals().get(participant.getId());
            if (total == null) throw new IllegalStateException("참가자 정산 결과가 누락되었습니다.");
            if (finalResult) participant.recordFinal(total.kills(), total.matchCount());
            else participant.recordInterim(total.kills(), total.matchCount());
        }
    }

    private List<CommunityMember> winnerMembers(KillCompetition competition) {
        if (competition.getGameMode() == KillCompetitionGameMode.SOLO) {
            int max = competition.getParticipants().stream().map(KillCompetitionParticipant::getFinalKills)
                    .filter(Objects::nonNull).mapToInt(Integer::intValue).max().orElse(0);
            return competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved)
                    .filter(p -> Objects.equals(p.getFinalKills(), max))
                    .filter(p -> Optional.ofNullable(p.getFinalMatchCount()).orElse(0) > 0)
                    .map(KillCompetitionParticipant::getCommunityMember).toList();
        }
        Map<Long, Integer> totals = new HashMap<>();
        competition.getTeams().forEach(team -> totals.put(team.getId(), 0));
        competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved)
                .filter(p -> p.getTeam() != null).forEach(p -> totals.merge(p.getTeam().getId(), p.getFinalKills(), Integer::sum));
        int max = totals.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        Set<Long> winningTeams = new HashSet<>();
        totals.forEach((teamId, kills) -> { if (kills == max) winningTeams.add(teamId); });
        return competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved)
                .filter(p -> p.getTeam() != null && winningTeams.contains(p.getTeam().getId()))
                .filter(p -> Optional.ofNullable(p.getFinalMatchCount()).orElse(0) > 0)
                .map(KillCompetitionParticipant::getCommunityMember).toList();
    }

    private boolean activeClaim(Instant claim, Instant now) {
        return claim != null && now.isBefore(claim.plus(CLAIM_TIMEOUT));
    }
    private KillCompetition requireForUpdate(Long communityId, Long id) {
        return competitions.findForUpdate(communityId, id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "킬내기를 찾을 수 없습니다."));
    }
    private void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private void forbidden(String message) { throw new ResponseStatusException(HttpStatus.FORBIDDEN, message); }
}
