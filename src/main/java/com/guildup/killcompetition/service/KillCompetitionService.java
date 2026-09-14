package com.guildup.killcompetition.service;

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
    private final CommunityMemberPubgIdentityService pubgIdentities;
    private final Clock clock;

    public KillCompetitionService(KillCompetitionRepository competitions,
                                  KillCompetitionParticipantRepository participants,
                                  KillCompetitionTeamRepository teamRepository,
                                  KillCompetitionMatchResultRepository matchResults,
                                  CurrentCommunityMemberService currentMembers,
                                  CommunityAccessService access,
                                  CommunityMemberPubgIdentityService pubgIdentities, Clock clock) {
        this.competitions = competitions; this.participants = participants; this.teamRepository = teamRepository;
        this.matchResults = matchResults;
        this.currentMembers = currentMembers; this.access = access;
        this.pubgIdentities = pubgIdentities; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<KillCompetitionSummaryResponse> list(Long userId, Long communityId) {
        access.requireCommunityMember(userId, communityId);
        Instant now = clock.instant();
        return competitions.findByCommunityIdOrderByCreatedAtDesc(communityId).stream()
                .map(item -> KillCompetitionSummaryResponse.from(item, now)).toList();
    }

    @Transactional(readOnly = true)
    public KillCompetitionDetailResponse get(Long userId, Long communityId, Long competitionId) {
        CommunityUser membership = access.requireCommunityMember(userId, communityId);
        KillCompetition competition = requireDetail(communityId, competitionId);
        CommunityMember current = currentMembers.find(userId, communityId).orElse(null);
        boolean configured = current != null && pubgIdentities.find(current).isPresent();
        List<KillCompetitionMatchResult> results = competition.getStatus() == KillCompetitionStatus.COMPLETED
                ? matchResults.findByCompetitionIdOrderByMatchStartedAtAscMatchIdAscParticipantIdAsc(competitionId)
                : List.of();
        return KillCompetitionDetailResponse.from(competition, current == null ? null : current.getId(),
                isAdmin(membership), configured, clock.instant(), results);
    }

    @Transactional
    public KillCompetitionDetailResponse create(Long userId, Long communityId, KillCompetitionCreateRequest request) {
        CommunityMember creator = currentMembers.require(userId, communityId);
        if (request == null || request.title() == null || request.title().isBlank()
                || request.title().trim().length() > 100) bad("제목은 1~100자로 입력해 주세요.");
        if (request.gameMode() == null) bad("게임 방식을 선택해 주세요.");
        Instant now = clock.instant();
        if (request.endsAt() == null || !request.endsAt().isAfter(now)) bad("종료 시각은 현재보다 이후여야 합니다.");
        KillCompetition saved = competitions.save(new KillCompetition(
                creator.getCommunity(), creator, request.title().trim(), request.gameMode(), request.endsAt(), now));
        return KillCompetitionDetailResponse.from(saved, creator.getId(), false,
                hasUsablePubgAccount(creator), now, List.of());
    }

    @Transactional
    public KillCompetitionDetailResponse leave(Long userId, Long communityId, Long competitionId) {
        KillCompetition competition = requireForUpdate(communityId, competitionId);
        if (competition.getStatus() != KillCompetitionStatus.RECRUITING) conflict("참가 모집이 종료되어 취소할 수 없습니다.");
        CommunityMember member = currentMembers.require(userId, communityId);
        KillCompetitionParticipant participant = participants
                .findByCompetitionIdAndCommunityMemberId(competitionId, member.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "참가 중인 킬내기가 아닙니다."));
        competition.removeParticipant(participant);
        participants.delete(participant);
        return detailForMutation(competition, member.getId(), userId);
    }

    @Transactional
    public KillCompetitionDetailResponse closeRecruitment(Long userId, Long communityId, Long competitionId) {
        KillCompetition competition = requireForUpdate(communityId, competitionId);
        requireCreator(userId, communityId, competition);
        if (competition.getStatus() != KillCompetitionStatus.RECRUITING) conflict("현재 모집 중인 킬내기가 아닙니다.");
        if (competition.getParticipants().isEmpty()) conflict("참가자가 한 명 이상이어야 모집을 마감할 수 있습니다.");
        competition.closeRecruitment(clock.instant());
        return detailForMutation(competition, competition.getCreatedBy().getId(), userId);
    }

    @Transactional
    public KillCompetitionDetailResponse configureTeams(Long userId, Long communityId, Long competitionId,
                                                        KillCompetitionTeamRequest request) {
        KillCompetition competition = requireForUpdate(communityId, competitionId);
        requireCreator(userId, communityId, competition);
        if (competition.getStatus() != KillCompetitionStatus.READY) conflict("시작 전 준비 상태에서만 팀을 구성할 수 있습니다.");
        if (competition.getGameMode() == KillCompetitionGameMode.SOLO) bad("SOLO 방식에는 팀 구성이 필요하지 않습니다.");
        if (request == null || request.teamCount() < 2 || request.teamCount() > 20) bad("팀 수는 2~20개로 설정해 주세요.");
        List<KillCompetitionTeamRequest.TeamAssignment> assignments = request.assignments() == null
                ? List.of() : request.assignments();
        if (assignments.size() != competition.getParticipants().size()) bad("모든 참가자를 한 팀에 배정해 주세요.");
        Set<Long> assignedIds = new HashSet<>();
        for (var assignment : assignments) {
            if (assignment.participantId() == null || !assignedIds.add(assignment.participantId())) {
                bad("한 참가자는 하나의 팀에만 배정할 수 있습니다.");
            }
            if (assignment.teamNumber() < 1 || assignment.teamNumber() > request.teamCount()) bad("올바르지 않은 팀 번호입니다.");
        }
        Set<Long> participantIds = competition.getParticipants().stream()
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
        Map<Long, KillCompetitionParticipant> byId = competition.getParticipants().stream().collect(Collectors.toMap(
                KillCompetitionParticipant::getId, Function.identity()));
        assignments.forEach(item -> byId.get(item.participantId()).assignTeam(byNumber.get(item.teamNumber())));
        return detailForMutation(competition, competition.getCreatedBy().getId(), userId);
    }

    @Transactional
    public KillCompetitionDetailResponse start(Long userId, Long communityId, Long competitionId) {
        KillCompetition competition = requireForUpdate(communityId, competitionId);
        requireCreator(userId, communityId, competition);
        if (competition.getStatus() != KillCompetitionStatus.READY) conflict("시작 준비 상태에서만 킬내기를 시작할 수 있습니다.");
        Instant now = clock.instant();
        if (!now.isBefore(competition.getEndsAt())) conflict("종료 시각이 지나 킬내기를 시작할 수 없습니다.");
        if (competition.getParticipants().isEmpty()) conflict("참가자가 한 명 이상이어야 시작할 수 있습니다.");
        if (competition.getGameMode() != KillCompetitionGameMode.SOLO
                && (competition.getTeams().size() < 2 || competition.getParticipants().stream().anyMatch(p -> p.getTeam() == null))) {
            conflict("모든 참가자의 팀 구성을 완료해 주세요.");
        }
        competition.start(now);
        return detailForMutation(competition, competition.getCreatedBy().getId(), userId);
    }

    @Transactional
    public KillCompetitionDetailResponse cancel(Long userId, Long communityId, Long competitionId) {
        CommunityUser membership = access.requireCommunityAdmin(userId, communityId);
        KillCompetition competition = requireForUpdate(communityId, competitionId);
        if (competition.getStatus() == KillCompetitionStatus.COMPLETED) conflict("결과가 확정된 킬내기는 취소할 수 없습니다.");
        competition.cancel(clock.instant());
        CommunityMember current = currentMembers.find(userId, communityId).orElse(null);
        return KillCompetitionDetailResponse.from(competition, current == null ? null : current.getId(),
                isAdmin(membership), current != null && pubgIdentities.find(current).isPresent(), clock.instant(), List.of());
    }

    private KillCompetitionDetailResponse detailForMutation(KillCompetition competition, Long memberId, Long userId) {
        CommunityUser membership = access.requireCommunityMember(userId, competition.getCommunity().getId());
        CommunityMember current = currentMembers.find(userId, competition.getCommunity().getId()).orElse(null);
        return KillCompetitionDetailResponse.from(competition, memberId, isAdmin(membership),
                current != null && pubgIdentities.find(current).isPresent(), clock.instant(), List.of());
    }
    private void requireCreator(Long userId, Long communityId, KillCompetition competition) {
        CommunityMember member = currentMembers.require(userId, communityId);
        if (!Objects.equals(member.getId(), competition.getCreatedBy().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "킬내기 생성자만 수행할 수 있습니다.");
        }
    }
    private KillCompetition requireDetail(Long communityId, Long id) {
        return competitions.findDetail(communityId, id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "킬내기를 찾을 수 없습니다."));
    }
    private KillCompetition requireForUpdate(Long communityId, Long id) {
        return competitions.findForUpdate(communityId, id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "킬내기를 찾을 수 없습니다."));
    }
    private boolean hasUsablePubgAccount(CommunityMember member) {
        return pubgIdentities.find(member).isPresent();
    }
    private boolean isAdmin(CommunityUser membership) {
        return membership.getRole() == CommunityUserRole.OWNER || membership.getRole() == CommunityUserRole.ADMIN;
    }
    private void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private void conflict(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
