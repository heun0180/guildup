package com.guildup.killcompetition.service;

import com.guildup.bingo.service.BingoGuildUpContentService;
import com.guildup.community.domain.*;
import com.guildup.community.repository.*;
import com.guildup.community.service.*;
import com.guildup.killcompetition.domain.*;
import com.guildup.killcompetition.dto.*;
import com.guildup.killcompetition.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KillCompetitionService {
    private final KillCompetitionRepository competitions;
    private final KillCompetitionParticipantRepository participants;
    private final KillCompetitionTeamRepository teamRepository;
    private final KillCompetitionMatchResultRepository matchResults;
    private final CurrentCommunityMemberService currentMembers;
    private final CommunityAccessService access;
    private final CommunityGameAccessService gameAccess;
    private final CommunityMemberPubgIdentityService pubgIdentities;
    private final KillCompetitionManagementAccess managementAccess;
    private final CommunityScoreService scores;
    private final BingoGuildUpContentService bingoContent;
    private final Clock clock;

    public KillCompetitionService(KillCompetitionRepository competitions,
                                  KillCompetitionParticipantRepository participants,
                                  KillCompetitionTeamRepository teamRepository,
                                  KillCompetitionMatchResultRepository matchResults,
                                  CurrentCommunityMemberService currentMembers,
                                  CommunityAccessService access,
                                  CommunityGameAccessService gameAccess,
                                  CommunityMemberPubgIdentityService pubgIdentities,
                                  KillCompetitionManagementAccess managementAccess,
                                  CommunityScoreService scores,
                                  BingoGuildUpContentService bingoContent, Clock clock) {
        this.competitions = competitions; this.participants = participants; this.teamRepository = teamRepository;
        this.matchResults = matchResults;
        this.currentMembers = currentMembers; this.access = access; this.gameAccess = gameAccess;
        this.pubgIdentities = pubgIdentities; this.managementAccess = managementAccess;
        this.scores = scores; this.bingoContent = bingoContent; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<KillCompetitionSummaryResponse> list(Long userId, Long communityId, Long communityGameId) {
        gameAccess.requireAccessible(userId, communityId, communityGameId, GameCapability.KILL_COMPETITION);
        Instant now = clock.instant();
        return competitions.findByCommunityGameIdOrderByCreatedAtDesc(communityGameId).stream()
                .map(item -> KillCompetitionSummaryResponse.from(item, now)).toList();
    }
    public List<KillCompetitionSummaryResponse> list(Long userId, Long communityId) {
        return list(userId, communityId, gameAccess.requireOnlyAccessible(userId, communityId, GameCapability.KILL_COMPETITION).getId());
    }

    @Transactional(readOnly = true)
    public KillCompetitionDetailResponse get(Long userId, Long communityId, Long communityGameId, Long competitionId) {
        gameAccess.requireAccessible(userId, communityId, communityGameId, GameCapability.KILL_COMPETITION);
        CommunityUser membership = access.requireCommunityMember(userId, communityId);
        KillCompetition competition = requireDetail(communityId, communityGameId, competitionId);
        CommunityMember current = currentMembers.find(userId, communityId).orElse(null);
        boolean configured = current != null && pubgIdentities.find(current, competition.getCommunityGame()).isPresent();
        List<KillCompetitionMatchResult> results = competition.getStatus() == KillCompetitionStatus.COMPLETED
                ? matchResults.findByCompetitionIdOrderByMatchStartedAtAscMatchIdAscParticipantIdAsc(competitionId)
                : List.of();
        boolean administrator = managementAccess.isCommunityAdministrator(membership);
        return KillCompetitionDetailResponse.from(competition, current == null ? null : current.getId(),
                administrator, managementAccess.canManage(userId, communityId, competition),
                configured, clock.instant(), results);
    }

    /** Internal follow-up operations already identify a persisted competition and use its stored game. */
    @Transactional(readOnly = true)
    public KillCompetitionDetailResponse get(Long userId, Long communityId, Long competitionId) {
        KillCompetition competition = competitions.findDetail(communityId, competitionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "킬내기를 찾을 수 없습니다."));
        return get(userId, communityId, competition.getCommunityGame().getId(), competitionId);
    }

    @Transactional
    public KillCompetitionDetailResponse create(Long userId, Long communityId, Long communityGameId, KillCompetitionCreateRequest request) {
        CommunityGame game = gameAccess.requireAccessible(userId, communityId, communityGameId, GameCapability.KILL_COMPETITION);
        CommunityMember creator = currentMembers.require(userId, communityId);
        if (request == null || request.title() == null || request.title().isBlank()
                || request.title().trim().length() > 100) bad("제목은 1~100자로 입력해 주세요.");
        if (request.gameMode() == null) bad("게임 방식을 선택해 주세요.");
        Instant now = clock.instant();
        if (request.endsAt() == null || !request.endsAt().isAfter(now)) bad("종료 시각은 현재보다 이후여야 합니다.");
        KillCompetition saved = competitions.save(new KillCompetition(
                creator.getCommunity(), game, creator, request.title().trim(), request.gameMode(), request.endsAt(), now));
        CommunityUser membership = access.requireCommunityMember(userId, communityId);
        return KillCompetitionDetailResponse.from(saved, creator.getId(),
                managementAccess.isCommunityAdministrator(membership), true,
                hasUsablePubgAccount(creator, game), now, List.of());
    }
    public KillCompetitionDetailResponse create(Long userId, Long communityId, KillCompetitionCreateRequest request) {
        return create(userId, communityId,
                gameAccess.requireOnlyAccessible(userId, communityId, GameCapability.KILL_COMPETITION).getId(), request);
    }

    @Transactional
    public KillCompetitionDetailResponse leave(Long userId, Long communityId, Long communityGameId, Long competitionId) {
        KillCompetition competition = requireForUpdate(communityId, communityGameId, competitionId);
        Instant now = clock.instant();
        if (!now.isBefore(competition.getEndsAt()) || !Set.of(KillCompetitionStatus.RECRUITING,
                KillCompetitionStatus.READY, KillCompetitionStatus.IN_PROGRESS).contains(competition.getStatus())) {
            conflict("종료되었거나 결과 발표 중인 킬내기에서는 참가를 취소할 수 없습니다.");
        }
        CommunityMember member = currentMembers.require(userId, communityId);
        KillCompetitionParticipant participant = participants
                .findByCompetitionIdAndCommunityMemberId(competitionId, member.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "참가 중인 킬내기가 아닙니다."));
        if (competition.getStatus() == KillCompetitionStatus.IN_PROGRESS && participant.isApproved()) {
            conflict("진행 중인 참가자는 킬내기 관리자만 제외할 수 있습니다.");
        }
        competition.removeParticipant(participant);
        participants.delete(participant);
        return detailForMutation(competition, userId);
    }
    @Transactional public KillCompetitionDetailResponse leave(Long userId, Long communityId, Long competitionId) { return leave(userId, communityId, gameId(communityId, competitionId), competitionId); }

    @Transactional
    public KillCompetitionDetailResponse closeRecruitment(Long userId, Long communityId, Long communityGameId, Long competitionId) {
        KillCompetition competition = requireForUpdate(communityId, communityGameId, competitionId);
        managementAccess.requireCanManage(userId, communityId, competition);
        if (competition.getStatus() != KillCompetitionStatus.RECRUITING) conflict("현재 모집 중인 킬내기가 아닙니다.");
        requireCompetitionMutable(competition);
        if (approved(competition).isEmpty()) conflict("참가자가 한 명 이상이어야 시작 준비로 전환할 수 있습니다.");
        competition.closeRecruitment(clock.instant());
        return detailForMutation(competition, userId);
    }
    @Transactional public KillCompetitionDetailResponse closeRecruitment(Long userId, Long communityId, Long competitionId) { return closeRecruitment(userId, communityId, gameId(communityId, competitionId), competitionId); }

    @Transactional
    public KillCompetitionDetailResponse configureTeams(Long userId, Long communityId, Long communityGameId, Long competitionId,
                                                        KillCompetitionTeamRequest request) {
        KillCompetition competition = requireForUpdate(communityId, communityGameId, competitionId);
        managementAccess.requireCanManage(userId, communityId, competition);
        if (competition.getStatus() != KillCompetitionStatus.READY) conflict("시작 전 준비 상태에서만 팀을 구성할 수 있습니다.");
        requireCompetitionMutable(competition);
        if (competition.getGameMode() == KillCompetitionGameMode.SOLO) bad("SOLO 방식에는 팀 구성이 필요하지 않습니다.");
        if (request == null || request.teamCount() < 2 || request.teamCount() > 20) bad("팀 수는 2~20개로 설정해 주세요.");
        List<KillCompetitionTeamRequest.TeamAssignment> assignments = request.assignments() == null
                ? List.of() : request.assignments();
        List<KillCompetitionParticipant> approved = approved(competition);
        if (assignments.size() != approved.size()) bad("모든 승인 참가자를 한 팀에 배정해 주세요.");
        Set<Long> assignedIds = new HashSet<>();
        for (var assignment : assignments) {
            if (assignment.participantId() == null || !assignedIds.add(assignment.participantId())) {
                bad("한 참가자는 하나의 팀에만 배정할 수 있습니다.");
            }
            if (assignment.teamNumber() < 1 || assignment.teamNumber() > request.teamCount()) bad("올바르지 않은 팀 번호입니다.");
        }
        Set<Long> participantIds = approved.stream()
                .map(KillCompetitionParticipant::getId).collect(Collectors.toSet());
        if (!participantIds.equals(assignedIds)) bad("다른 킬내기의 참가자를 배정할 수 없습니다.");

        competition.replaceTeams(List.of());
        competitions.flush();
        List<KillCompetitionTeam> teams = new ArrayList<>();
        for (int i = 1; i <= request.teamCount(); i++) teams.add(new KillCompetitionTeam(competition, "TEAM " + i, i));
        competition.replaceTeams(teams);
        teamRepository.saveAllAndFlush(teams);
        Map<Integer, KillCompetitionTeam> byNumber = teams.stream().collect(Collectors.toMap(
                KillCompetitionTeam::getDisplayOrder, Function.identity()));
        Map<Long, KillCompetitionParticipant> byId = approved.stream().collect(Collectors.toMap(
                KillCompetitionParticipant::getId, Function.identity()));
        assignments.forEach(item -> byId.get(item.participantId()).assignTeam(byNumber.get(item.teamNumber())));
        return detailForMutation(competition, userId);
    }
    @Transactional public KillCompetitionDetailResponse configureTeams(Long userId, Long communityId, Long competitionId, KillCompetitionTeamRequest request) { return configureTeams(userId, communityId, gameId(communityId, competitionId), competitionId, request); }

    @Transactional
    public KillCompetitionDetailResponse start(Long userId, Long communityId, Long communityGameId, Long competitionId) {
        KillCompetition competition = requireForUpdate(communityId, communityGameId, competitionId);
        managementAccess.requireCanManage(userId, communityId, competition);
        if (competition.getStatus() != KillCompetitionStatus.READY) conflict("시작 준비 상태에서만 킬내기를 시작할 수 있습니다.");
        Instant now = clock.instant();
        if (!now.isBefore(competition.getEndsAt())) conflict("종료 시각이 지나 킬내기를 시작할 수 없습니다.");
        if (approved(competition).isEmpty()) conflict("승인된 참가자가 한 명 이상이어야 시작할 수 있습니다.");
        if (competition.getGameMode() != KillCompetitionGameMode.SOLO
                && (competition.getTeams().size() < 2 || approved(competition).stream().anyMatch(p -> p.getTeam() == null))) {
            conflict("모든 참가자의 팀 구성을 완료해 주세요.");
        }
        competition.start(now);
        return detailForMutation(competition, userId);
    }
    @Transactional public KillCompetitionDetailResponse start(Long userId, Long communityId, Long competitionId) { return start(userId, communityId, gameId(communityId, competitionId), competitionId); }

    @Transactional
    public KillCompetitionDetailResponse cancel(Long userId, Long communityId, Long communityGameId, Long competitionId) {
        KillCompetition competition = requireForUpdate(communityId, communityGameId, competitionId);
        managementAccess.requireCanManage(userId, communityId, competition);
        if (Set.of(KillCompetitionStatus.RESULT_PENDING, KillCompetitionStatus.COMPLETED).contains(competition.getStatus())) {
            conflict("결과 발표 요청 이후에는 킬내기를 취소할 수 없습니다.");
        }
        competition.cancel(clock.instant());
        return detailForMutation(competition, userId);
    }
    @Transactional public KillCompetitionDetailResponse cancel(Long userId, Long communityId, Long competitionId) { return cancel(userId, communityId, gameId(communityId, competitionId), competitionId); }

    @Transactional
    public KillCompetitionDetailResponse setRecruitment(Long userId, Long communityId, Long communityGameId, Long competitionId, boolean open) {
        KillCompetition competition = requireForUpdate(communityId, communityGameId, competitionId);
        managementAccess.requireCanManage(userId, communityId, competition);
        Instant now = clock.instant();
        if (!now.isBefore(competition.getEndsAt()) || !Set.of(KillCompetitionStatus.RECRUITING,
                KillCompetitionStatus.READY, KillCompetitionStatus.IN_PROGRESS).contains(competition.getStatus())) {
            conflict("종료되었거나 결과 발표 중인 킬내기의 참가 신청 상태는 변경할 수 없습니다.");
        }
        competition.setRecruitmentOpen(open, now);
        return detailForMutation(competition, userId);
    }
    @Transactional public KillCompetitionDetailResponse setRecruitment(Long userId, Long communityId, Long competitionId, boolean open) { return setRecruitment(userId, communityId, gameId(communityId, competitionId), competitionId, open); }

    @Transactional
    public KillCompetitionDetailResponse approveParticipant(Long userId, Long communityId, Long communityGameId, Long competitionId,
                                                             Long participantId, Long teamId) {
        KillCompetition competition = requireForUpdate(communityId, communityGameId, competitionId);
        managementAccess.requireCanManage(userId, communityId, competition);
        requireInProgressMutable(competition);
        KillCompetitionParticipant participant = requireParticipant(competition, participantId);
        if (participant.isApproved()) conflict("이미 승인된 참가자입니다.");
        KillCompetitionTeam team = resolveTeamForActiveCompetition(competition, teamId);
        participant.approve(clock.instant(), team);
        return detailForMutation(competition, userId);
    }
    @Transactional public KillCompetitionDetailResponse approveParticipant(Long userId, Long communityId, Long competitionId, Long participantId, Long teamId) { return approveParticipant(userId, communityId, gameId(communityId, competitionId), competitionId, participantId, teamId); }

    @Transactional
    public KillCompetitionDetailResponse rejectParticipant(Long userId, Long communityId, Long communityGameId, Long competitionId,
                                                            Long participantId) {
        KillCompetition competition = requireForUpdate(communityId, communityGameId, competitionId);
        managementAccess.requireCanManage(userId, communityId, competition);
        requireInProgressMutable(competition);
        KillCompetitionParticipant participant = requireParticipant(competition, participantId);
        if (participant.isApproved()) conflict("승인된 참가자는 제외 기능을 사용해 주세요.");
        competition.removeParticipant(participant);
        participants.delete(participant);
        return detailForMutation(competition, userId);
    }
    @Transactional public KillCompetitionDetailResponse rejectParticipant(Long userId, Long communityId, Long competitionId, Long participantId) { return rejectParticipant(userId, communityId, gameId(communityId, competitionId), competitionId, participantId); }

    @Transactional
    public KillCompetitionDetailResponse removeParticipant(Long userId, Long communityId, Long communityGameId, Long competitionId,
                                                            Long participantId) {
        KillCompetition competition = requireForUpdate(communityId, communityGameId, competitionId);
        managementAccess.requireCanManage(userId, communityId, competition);
        requireCompetitionMutable(competition);
        KillCompetitionParticipant participant = requireParticipant(competition, participantId);
        if (hasRecognizedMatch(participant)) conflict("이미 경기 기록이 집계된 참가자는 진행 중에 제외할 수 없습니다.");
        competition.removeParticipant(participant);
        participants.delete(participant);
        return detailForMutation(competition, userId);
    }
    @Transactional public KillCompetitionDetailResponse removeParticipant(Long userId, Long communityId, Long competitionId, Long participantId) { return removeParticipant(userId, communityId, gameId(communityId, competitionId), competitionId, participantId); }

    @Transactional
    public KillCompetitionDetailResponse changeParticipantTeam(Long userId, Long communityId, Long communityGameId, Long competitionId,
                                                                Long participantId, Long teamId) {
        KillCompetition competition = requireForUpdate(communityId, communityGameId, competitionId);
        managementAccess.requireCanManage(userId, communityId, competition);
        requireCompetitionMutable(competition);
        if (competition.getGameMode() == KillCompetitionGameMode.SOLO) bad("SOLO 참가자는 팀을 변경할 수 없습니다.");
        KillCompetitionParticipant participant = requireParticipant(competition, participantId);
        if (!participant.isApproved()) conflict("승인 대기 참가자는 승인하면서 팀을 배정해 주세요.");
        if (hasRecognizedMatch(participant)) conflict("이미 경기 기록이 집계된 참가자는 진행 중에 팀을 변경할 수 없습니다.");
        participant.assignTeam(resolveTeamForActiveCompetition(competition, teamId));
        return detailForMutation(competition, userId);
    }
    @Transactional public KillCompetitionDetailResponse changeParticipantTeam(Long userId, Long communityId, Long competitionId, Long participantId, Long teamId) { return changeParticipantTeam(userId, communityId, gameId(communityId, competitionId), competitionId, participantId, teamId); }

    @Transactional
    public KillCompetitionDetailResponse updateEndTime(Long userId, Long communityId, Long communityGameId,
                                                       Long competitionId, Instant endsAt) {
        KillCompetition competition = requireForUpdate(communityId, communityGameId, competitionId);
        managementAccess.requireCanManage(userId, communityId, competition);
        if (competition.getStartedAt() != null || !Set.of(KillCompetitionStatus.RECRUITING,
                KillCompetitionStatus.READY).contains(competition.getStatus())) {
            conflict("킬내기 시작 전까지만 종료 시각을 변경할 수 있습니다.");
        }
        Instant now = clock.instant();
        if (endsAt == null || !endsAt.isAfter(now)) bad("종료 시각은 현재보다 이후여야 합니다.");
        competition.updateEndsAt(endsAt, now);
        return detailForMutation(competition, userId);
    }

    @Transactional
    public void delete(Long userId, Long communityId, Long communityGameId, Long competitionId) {
        KillCompetition competition = requireForUpdate(communityId, communityGameId, competitionId);
        managementAccess.requireCanManage(userId, communityId, competition);
        Instant now = clock.instant();
        bingoContent.removeKillCompetition(competition.getId(), now);
        scores.removeKillCompetitionScores(competition.getId(), now);
        competitions.delete(competition);
        competitions.flush();
    }

    private void requireInProgressMutable(KillCompetition competition) {
        if (competition.getStatus() != KillCompetitionStatus.IN_PROGRESS) conflict("진행 중 참가 신청만 승인하거나 거절할 수 있습니다.");
        requireCompetitionMutable(competition);
    }

    private void requireCompetitionMutable(KillCompetition competition) {
        if (!clock.instant().isBefore(competition.getEndsAt()) || Set.of(KillCompetitionStatus.RESULT_PENDING,
                KillCompetitionStatus.COMPLETED, KillCompetitionStatus.CANCELLED).contains(competition.getStatus())) {
            conflict("종료되었거나 결과 발표 중인 킬내기의 참가자 구성은 변경할 수 없습니다.");
        }
    }

    private KillCompetitionParticipant requireParticipant(KillCompetition competition, Long participantId) {
        return competition.getParticipants().stream().filter(p -> Objects.equals(p.getId(), participantId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "참가자를 찾을 수 없습니다."));
    }

    private KillCompetitionTeam resolveTeamForActiveCompetition(KillCompetition competition, Long teamId) {
        if (competition.getGameMode() == KillCompetitionGameMode.SOLO) return null;
        if (teamId == null) bad("DUO/SQUAD 참가자는 팀을 선택해야 합니다.");
        return competition.getTeams().stream().filter(team -> Objects.equals(team.getId(), teamId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "올바른 팀을 선택해 주세요."));
    }

    private boolean hasRecognizedMatch(KillCompetitionParticipant participant) {
        return participant.getInterimMatchCount() > 0
                || Optional.ofNullable(participant.getFinalMatchCount()).orElse(0) > 0
                || matchResults.existsByParticipantId(participant.getId());
    }

    private List<KillCompetitionParticipant> approved(KillCompetition competition) {
        return competition.getParticipants().stream().filter(KillCompetitionParticipant::isApproved).toList();
    }

    private KillCompetitionDetailResponse detailForMutation(KillCompetition competition, Long userId) {
        CommunityUser membership = access.requireCommunityMember(userId, competition.getCommunity().getId());
        CommunityMember current = currentMembers.find(userId, competition.getCommunity().getId()).orElse(null);
        boolean administrator = managementAccess.isCommunityAdministrator(membership);
        return KillCompetitionDetailResponse.from(competition, current == null ? null : current.getId(),
                administrator, managementAccess.canManage(userId, competition.getCommunity().getId(), competition),
                current != null && pubgIdentities.find(current, competition.getCommunityGame()).isPresent(), clock.instant(), List.of());
    }
    private KillCompetition requireDetail(Long communityId, Long communityGameId, Long id) {
        return competitions.findDetail(communityId, communityGameId, id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "킬내기를 찾을 수 없습니다."));
    }
    private Long gameId(Long communityId, Long competitionId) {
        return competitions.findDetail(communityId, competitionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "킬내기를 찾을 수 없습니다."))
                .getCommunityGame().getId();
    }
    private KillCompetition requireForUpdate(Long communityId, Long communityGameId, Long id) {
        return competitions.findForUpdate(communityId, communityGameId, id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "킬내기를 찾을 수 없습니다."));
    }
    private boolean hasUsablePubgAccount(CommunityMember member, CommunityGame game) {
        return pubgIdentities.find(member, game).isPresent();
    }
    private void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
